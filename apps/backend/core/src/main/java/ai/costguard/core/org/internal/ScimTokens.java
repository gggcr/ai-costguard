package ai.costguard.core.org.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bearer tokens of the SCIM server (ADR 0043). A token is shown once, when it is issued; only its SHA-256 is kept,
 * so a leaked table gives nobody access. A revoked token stops working at once.
 */
@Component
class ScimTokens {

    static final String PREFIX = "cgscim_";
    private static final SecureRandom RANDOM = new SecureRandom();

    record Request(@NotBlank @Size(max = 100) String name, boolean dismissOnDeactivate) {
    }

    /** {@code token} is there only in the answer to issuing it. */
    record View(UUID id, String name, boolean dismissOnDeactivate, Instant createdAt, Instant lastUsedAt,
            Instant revokedAt, String token) {
    }

    /** Who a request to /scim/v2 acts for. */
    record Auth(UUID tenantId, UUID tokenId, boolean dismissOnDeactivate) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    ScimTokens(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    View issue(UUID tenantId, Request request) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                insert into scim_token (id, tenant_id, name, token_hash, dismiss_on_deactivate, created_at)
                values (?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, request.name().strip(), hash(token), request.dismissOnDeactivate(),
                Timestamp.from(now)).update();
        return new View(id, request.name().strip(), request.dismissOnDeactivate(), now, null, null, token);
    }

    @Transactional(readOnly = true)
    List<View> list(UUID tenantId) {
        return jdbc.sql("""
                select id, name, dismiss_on_deactivate, created_at, last_used_at, revoked_at from scim_token
                where tenant_id = ? order by created_at, id
                """).param(tenantId).query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("dismiss_on_deactivate"), rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("last_used_at")), instant(rs.getTimestamp("revoked_at")), null))
                .list();
    }

    @Transactional
    boolean revoke(UUID tenantId, UUID id) {
        return jdbc.sql("update scim_token set revoked_at = coalesce(revoked_at, ?) where id = ? and tenant_id = ?")
                .params(Timestamp.from(clock.instant()), id, tenantId).update() == 1;
    }

    /** The company of a token that is not revoked; its last use moves to now. */
    @Transactional
    Optional<Auth> authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Optional<Auth> auth = jdbc.sql("""
                select id, tenant_id, dismiss_on_deactivate from scim_token
                where token_hash = ? and revoked_at is null
                """).param(hash(token)).query((rs, n) -> new Auth(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getBoolean("dismiss_on_deactivate"))).optional();
        auth.ifPresent(a -> jdbc.sql("update scim_token set last_used_at = ? where id = ?")
                .params(Timestamp.from(clock.instant()), a.tokenId()).update());
        return auth;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
