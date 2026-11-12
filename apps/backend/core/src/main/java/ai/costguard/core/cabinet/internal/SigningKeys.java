package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.UserDirectory;
import ai.costguard.core.shared.crypto.Sealer;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The key a person signs documents with (ADR 0068): a code of an authenticator app (TOTP) or a six-digit PIN. It
 * belongs to the CostGuard account of the sign-in, found by the token's subject (sub), so the employer and the
 * accountant, who may not be employees, have one too. It no longer belongs to the token's e-mail: a person could
 * put someone else's address on their Keycloak account and take that person's key (the e-mail spoofing finding of
 * 2026-11-12). A code is never accepted twice, and five wrong codes in a row lock signing for 15 minutes.
 *
 * <p>The row keeps the address it was made for: it is the AAD of the sealed secret, so the secret still opens after
 * the account's address changes.
 */
@Service
class SigningKeys {

    static final String ISSUER = "CostGuard";
    private static final int MAX_FAILED = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private record Key(String email, String method, byte[] totpSecret, byte[] pendingSecret, String pinHash,
            Long lastStep, int failed, Instant lockedUntil) {
    }

    private final JdbcClient jdbc;
    private final Sealer sealer;
    private final TenantSession session;
    private final UserDirectory users;
    private final Clock clock;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    SigningKeys(JdbcClient jdbc, Sealer sealer, TenantSession session, UserDirectory users, Clock clock) {
        this.jdbc = jdbc;
        this.sealer = sealer;
        this.session = session;
        this.users = users;
        this.clock = clock;
    }

    /** The account of the token, in the bound tenant; a sign-in without one has no key. */
    UserDirectory.UserCard account(Jwt jwt) {
        return users.userBySubject(jwt.getSubject()).orElseThrow(CabinetProblem.SIGNING_NO_ACCOUNT::failure);
    }

    private static String aad(UUID tenantId, String email) {
        return "signing-key:" + tenantId + ":" + email;
    }

    private Optional<Key> key(UUID accountId, boolean lock) {
        return jdbc.sql("select * from signing_key where user_account_id = ?" + (lock ? " for update" : ""))
                .param(accountId)
                .query((rs, i) -> new Key(rs.getString("email"), rs.getString("method"), rs.getBytes("totp_secret"),
                        rs.getBytes("pending_secret"), rs.getString("pin_hash"), (Long) rs.getObject("last_step"),
                        rs.getInt("failed"), rs.getTimestamp("locked_until") == null ? null
                                : rs.getTimestamp("locked_until").toInstant()))
                .optional();
    }

    /** The address a new key of the account is made for; an existing key keeps its own (the AAD of its secret). */
    private String keyEmail(UserDirectory.UserCard account, Optional<Key> existing) {
        return existing.map(Key::email).orElse(account.email().strip().toLowerCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    SigningKeyStatus status(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        return status(account(jwt).id());
    }

    private SigningKeyStatus status(UUID accountId) {
        Optional<Key> k = key(accountId, false);
        Instant locked = k.map(Key::lockedUntil).filter(t -> t.isAfter(clock.instant())).orElse(null);
        return new SigningKeyStatus(k.map(Key::method).orElse(null), sealer.available(), locked);
    }

    /** A new authenticator secret, kept aside until a code from the app confirms it. */
    @Transactional
    TotpSetup startTotp(UUID tenantId, Jwt jwt, String current) {
        session.bind(tenantId);
        if (!sealer.available()) {
            throw CabinetProblem.SIGNING_KEY_UNAVAILABLE.failure();
        }
        UserDirectory.UserCard account = account(jwt);
        requireCurrent(tenantId, account.id(), current);
        String email = keyEmail(account, key(account.id(), false));
        String secret = Totp.newSecret();
        jdbc.sql("""
                insert into signing_key (tenant_id, user_account_id, email, pending_secret, updated_at)
                values (?, ?, ?, ?, ?)
                on conflict (user_account_id) do update set pending_secret = excluded.pending_secret,
                                                            updated_at = excluded.updated_at""")
                .params(tenantId, account.id(), email, sealer.seal(aad(tenantId, email), secret),
                        Timestamp.from(clock.instant()))
                .update();
        return new TotpSetup(secret, Totp.uri(ISSUER, email, secret));
    }

    @Transactional
    SigningKeyStatus confirmTotp(UUID tenantId, Jwt jwt, String code) {
        session.bind(tenantId);
        UUID accountId = account(jwt).id();
        Key k = key(accountId, true).filter(x -> x.pendingSecret() != null)
                .orElseThrow(CabinetProblem.SIGNING_NO_PENDING::failure);
        String secret = sealer.open(aad(tenantId, k.email()), k.pendingSecret());
        OptionalLong step = Totp.verify(secret, code, clock.instant().getEpochSecond());
        if (step.isEmpty()) {
            throw CabinetProblem.SIGNING_CODE_WRONG.failure();
        }
        jdbc.sql("""
                update signing_key set method = 'totp', totp_secret = pending_secret, pending_secret = null,
                       pin_hash = null, last_step = ?, failed = 0, locked_until = null, updated_at = ?
                where user_account_id = ?""").params(step.getAsLong(), Timestamp.from(clock.instant()), accountId)
                .update();
        return status(accountId);
    }

    @Transactional
    SigningKeyStatus setPin(UUID tenantId, Jwt jwt, String pin, String current) {
        session.bind(tenantId);
        if (pin == null || !pin.matches("\\d{6}") || pin.chars().distinct().count() == 1
                || "123456".equals(pin) || "654321".equals(pin)) {
            throw CabinetProblem.SIGNING_PIN_WEAK.failure();
        }
        UserDirectory.UserCard account = account(jwt);
        requireCurrent(tenantId, account.id(), current);
        String email = keyEmail(account, key(account.id(), false));
        jdbc.sql("""
                insert into signing_key (tenant_id, user_account_id, email, method, pin_hash, updated_at)
                values (?, ?, ?, 'pin', ?, ?)
                on conflict (user_account_id) do update set method = 'pin', pin_hash = excluded.pin_hash,
                       totp_secret = null, pending_secret = null, last_step = null, failed = 0, locked_until = null,
                       updated_at = excluded.updated_at""")
                .params(tenantId, account.id(), email, bcrypt.encode(pin), Timestamp.from(clock.instant())).update();
        return status(account.id());
    }

    /** Changing a key that is set needs the current code, so a borrowed laptop cannot replace it. */
    private void requireCurrent(UUID tenantId, UUID accountId, String current) {
        if (key(accountId, false).map(Key::method).isPresent()) {
            check(tenantId, accountId, current);
        }
    }

    /**
     * Checks a code of the account's key to sign with and returns the method, totp or pin. Call inside a transaction
     * bound to the tenant. A wrong code is counted in its own transaction, so the failure that follows does not roll
     * the count back.
     */
    String check(UUID tenantId, UUID accountId, String code) {
        // no row lock: a wrong code is counted in a transaction of its own, which would wait for it
        Key k = key(accountId, false).filter(x -> x.method() != null)
                .orElseThrow(CabinetProblem.SIGNING_KEY_MISSING::failure);
        Instant now = clock.instant();
        if (k.lockedUntil() != null && k.lockedUntil().isAfter(now)) {
            throw CabinetProblem.SIGNING_LOCKED.failure();
        }
        boolean ok;
        if ("totp".equals(k.method())) {
            OptionalLong step = Totp.verify(sealer.open(aad(tenantId, k.email()), k.totpSecret()), code,
                    now.getEpochSecond());
            // the step only moves forward, so the same code (or an older one) is never accepted twice
            ok = step.isPresent() && jdbc.sql("""
                    update signing_key set last_step = ?, failed = 0
                    where user_account_id = ? and (last_step is null or last_step < ?)""")
                    .params(step.getAsLong(), accountId, step.getAsLong()).update() == 1;
        } else {
            ok = code != null && bcrypt.matches(code.strip(), k.pinHash());
            if (ok) {
                jdbc.sql("update signing_key set failed = 0 where user_account_id = ?").param(accountId).update();
            }
        }
        if (!ok) {
            failures.wrong(tenantId, accountId, k.failed() + 1 >= MAX_FAILED ? now.plus(LOCK) : null);
            throw CabinetProblem.SIGNING_CODE_WRONG.failure();
        }
        return k.method();
    }

    private Failures failures;

    @org.springframework.beans.factory.annotation.Autowired
    void failures(Failures failures) {
        this.failures = failures;
    }

    /** Counts a wrong code apart from the caller's transaction, which the failure rolls back. */
    @org.springframework.stereotype.Component
    static class Failures {

        private final JdbcClient jdbc;
        private final TenantSession session;

        Failures(JdbcClient jdbc, TenantSession session) {
            this.jdbc = jdbc;
            this.session = session;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void wrong(UUID tenantId, UUID accountId, Instant lockUntil) {
            session.bind(tenantId);
            jdbc.sql("""
                    update signing_key set failed = case when ? then 0 else failed + 1 end, locked_until = ?
                    where user_account_id = ?""")
                    .params(lockUntil != null, lockUntil == null ? null : Timestamp.from(lockUntil), accountId)
                    .update();
        }
    }
}
