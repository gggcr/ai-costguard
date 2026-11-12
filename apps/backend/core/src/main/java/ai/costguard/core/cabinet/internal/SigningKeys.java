package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.crypto.Sealer;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * belongs to the e-mail of the sign-in, so the employer and the accountant, who may not be employees, have one too.
 * A code is never accepted twice, and five wrong codes in a row lock signing for 15 minutes.
 */
@Service
class SigningKeys {

    static final String ISSUER = "CostGuard";
    private static final int MAX_FAILED = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private record Key(String method, byte[] totpSecret, byte[] pendingSecret, String pinHash, Long lastStep,
            int failed, Instant lockedUntil) {
    }

    private final JdbcClient jdbc;
    private final Sealer sealer;
    private final TenantSession session;
    private final Clock clock;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    SigningKeys(JdbcClient jdbc, Sealer sealer, TenantSession session, Clock clock) {
        this.jdbc = jdbc;
        this.sealer = sealer;
        this.session = session;
        this.clock = clock;
    }

    static String email(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw CabinetProblem.SIGNING_NO_EMAIL.failure();
        }
        return email.strip().toLowerCase();
    }

    private static String aad(UUID tenantId, String email) {
        return "signing-key:" + tenantId + ":" + email;
    }

    private Optional<Key> key(String email, boolean lock) {
        return jdbc.sql("select * from signing_key where email = ?" + (lock ? " for update" : "")).param(email)
                .query((rs, i) -> new Key(rs.getString("method"), rs.getBytes("totp_secret"),
                        rs.getBytes("pending_secret"), rs.getString("pin_hash"), (Long) rs.getObject("last_step"),
                        rs.getInt("failed"), rs.getTimestamp("locked_until") == null ? null
                                : rs.getTimestamp("locked_until").toInstant()))
                .optional();
    }

    @Transactional(readOnly = true)
    SigningKeyStatus status(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        Optional<Key> k = key(email(jwt), false);
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
        String email = email(jwt);
        requireCurrent(tenantId, email, current);
        String secret = Totp.newSecret();
        jdbc.sql("""
                insert into signing_key (tenant_id, email, pending_secret, updated_at) values (?, ?, ?, ?)
                on conflict (tenant_id, email) do update set pending_secret = excluded.pending_secret,
                                                             updated_at = excluded.updated_at""")
                .params(tenantId, email, sealer.seal(aad(tenantId, email), secret), Timestamp.from(clock.instant()))
                .update();
        return new TotpSetup(secret, Totp.uri(ISSUER, email, secret));
    }

    @Transactional
    SigningKeyStatus confirmTotp(UUID tenantId, Jwt jwt, String code) {
        session.bind(tenantId);
        String email = email(jwt);
        Key k = key(email, true).filter(x -> x.pendingSecret() != null)
                .orElseThrow(CabinetProblem.SIGNING_NO_PENDING::failure);
        String secret = sealer.open(aad(tenantId, email), k.pendingSecret());
        OptionalLong step = Totp.verify(secret, code, clock.instant().getEpochSecond());
        if (step.isEmpty()) {
            throw CabinetProblem.SIGNING_CODE_WRONG.failure();
        }
        jdbc.sql("""
                update signing_key set method = 'totp', totp_secret = pending_secret, pending_secret = null,
                       pin_hash = null, last_step = ?, failed = 0, locked_until = null, updated_at = ?
                where email = ?""").params(step.getAsLong(), Timestamp.from(clock.instant()), email).update();
        return status(tenantId, jwt);
    }

    @Transactional
    SigningKeyStatus setPin(UUID tenantId, Jwt jwt, String pin, String current) {
        session.bind(tenantId);
        if (pin == null || !pin.matches("\\d{6}") || pin.chars().distinct().count() == 1
                || "123456".equals(pin) || "654321".equals(pin)) {
            throw CabinetProblem.SIGNING_PIN_WEAK.failure();
        }
        String email = email(jwt);
        requireCurrent(tenantId, email, current);
        jdbc.sql("""
                insert into signing_key (tenant_id, email, method, pin_hash, updated_at) values (?, ?, 'pin', ?, ?)
                on conflict (tenant_id, email) do update set method = 'pin', pin_hash = excluded.pin_hash,
                       totp_secret = null, pending_secret = null, last_step = null, failed = 0, locked_until = null,
                       updated_at = excluded.updated_at""")
                .params(tenantId, email, bcrypt.encode(pin), Timestamp.from(clock.instant())).update();
        return status(tenantId, jwt);
    }

    /** Changing a key that is set needs the current code, so a borrowed laptop cannot replace it. */
    private void requireCurrent(UUID tenantId, String email, String current) {
        if (key(email, false).map(Key::method).isPresent()) {
            check(tenantId, email, current);
        }
    }

    /**
     * Checks a code to sign with and returns the method, totp or pin. A wrong code is counted in its own transaction,
     * so the failure that follows does not roll the count back.
     */
    String check(UUID tenantId, String email, String code) {
        // no row lock: a wrong code is counted in a transaction of its own, which would wait for it
        Key k = key(email, false).filter(x -> x.method() != null)
                .orElseThrow(CabinetProblem.SIGNING_KEY_MISSING::failure);
        Instant now = clock.instant();
        if (k.lockedUntil() != null && k.lockedUntil().isAfter(now)) {
            throw CabinetProblem.SIGNING_LOCKED.failure();
        }
        boolean ok;
        if ("totp".equals(k.method())) {
            OptionalLong step = Totp.verify(sealer.open(aad(tenantId, email), k.totpSecret()), code,
                    now.getEpochSecond());
            // the step only moves forward, so the same code (or an older one) is never accepted twice
            ok = step.isPresent() && jdbc.sql("""
                    update signing_key set last_step = ?, failed = 0
                    where email = ? and (last_step is null or last_step < ?)""")
                    .params(step.getAsLong(), email, step.getAsLong()).update() == 1;
        } else {
            ok = code != null && bcrypt.matches(code.strip(), k.pinHash());
            if (ok) {
                jdbc.sql("update signing_key set failed = 0 where email = ?").param(email).update();
            }
        }
        if (!ok) {
            failures.wrong(tenantId, email, k.failed() + 1 >= MAX_FAILED ? now.plus(LOCK) : null);
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
        public void wrong(UUID tenantId, String email, Instant lockUntil) {
            session.bind(tenantId);
            jdbc.sql("""
                    update signing_key set failed = case when ? then 0 else failed + 1 end, locked_until = ?
                    where email = ?""")
                    .params(lockUntil != null, lockUntil == null ? null : Timestamp.from(lockUntil), email).update();
        }
    }
}
