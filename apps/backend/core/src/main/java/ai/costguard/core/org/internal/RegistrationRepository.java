package ai.costguard.core.org.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Registrations and invitations. registration and invitation are scoped by RLS; registration_timer (ids and
 * dates) and registration_attempt (hashes, no company) are read across companies.
 */
@Repository
class RegistrationRepository {

    record Pending(UUID tenantId, String accountId, String email, boolean confirmed) {
    }

    private static final RowMapper<Invitation> INVITATION = (rs, n) -> new Invitation(
            rs.getObject("id", UUID.class), rs.getObject("user_account_id", UUID.class), rs.getString("email"),
            UserRole.valueOf(rs.getString("role")), InvitationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant());

    private final JdbcClient jdbc;

    RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Companies

    void insertTenant(UUID id, String name, String currency, String country) {
        jdbc.sql("insert into tenant (id, name, currency, country, status) values (?, ?, ?, ?, 'pending')")
                .params(id, name, currency, country).update();
    }

    Optional<String> tenantStatus() {
        return jdbc.sql("select status from tenant").query(String.class).optional();
    }

    /** True when it was pending. */
    boolean activate() {
        return jdbc.sql("update tenant set status = 'active' where status = 'pending'").update() > 0;
    }

    void deleteTenant(UUID tenantId) {
        jdbc.sql("delete from user_account").update();
        jdbc.sql("delete from registration").update();
        jdbc.sql("delete from registration_timer where tenant_id = ?").param(tenantId).update();
        jdbc.sql("delete from tenant where id = ?").param(tenantId).update();
    }

    // --- Registrations

    void insertRegistration(UUID tenantId, String accountId, String email, Instant now, Instant expires) {
        jdbc.sql("""
                insert into registration (tenant_id, keycloak_user_id, email, created_at) values (?, ?, ?, ?)
                """).params(tenantId, accountId, email, Timestamp.from(now)).update();
        jdbc.sql("insert into registration_timer (tenant_id, expires_at) values (?, ?)")
                .params(tenantId, Timestamp.from(expires)).update();
    }

    Optional<Pending> registration() {
        return jdbc.sql("select tenant_id, keycloak_user_id, email, confirmed_at from registration")
                .query((rs, n) -> new Pending(rs.getObject("tenant_id", UUID.class), rs.getString("keycloak_user_id"),
                        rs.getString("email"), rs.getTimestamp("confirmed_at") != null))
                .optional();
    }

    void confirmed(UUID tenantId, Instant now) {
        jdbc.sql("update registration set confirmed_at = ? where confirmed_at is null").param(Timestamp.from(now))
                .update();
        jdbc.sql("delete from registration_timer where tenant_id = ?").param(tenantId).update();
    }

    List<UUID> expired(Instant now, int limit) {
        return jdbc.sql("select tenant_id from registration_timer where expires_at <= ? order by expires_at limit ?")
                .params(Timestamp.from(now), limit).query(UUID.class).list();
    }

    /** Another instance holding the row has it: false, and that one handles it. */
    boolean lockTimer(UUID tenantId) {
        return jdbc.sql("select tenant_id from registration_timer where tenant_id = ? for update skip locked")
                .param(tenantId).query(UUID.class).optional().isPresent();
    }

    // --- Rate limit

    void attempt(String kind, String keyHash, Instant at) {
        jdbc.sql("insert into registration_attempt (kind, key_hash, at) values (?, ?, ?)")
                .params(kind, keyHash, Timestamp.from(at)).update();
    }

    long attempts(String kind, String keyHash, Instant since) {
        return jdbc.sql("select count(*) from registration_attempt where kind = ? and key_hash = ? and at > ?")
                .params(kind, keyHash, Timestamp.from(since)).query(Long.class).single();
    }

    int forgetAttempts(Instant before) {
        return jdbc.sql("delete from registration_attempt where at <= ?").param(Timestamp.from(before)).update();
    }

    // --- Invitations

    void insertInvitation(UUID id, UUID tenantId, UUID userId, String email, UserRole role, String accountId,
            Instant now) {
        jdbc.sql("""
                insert into invitation (id, tenant_id, user_account_id, email, role, keycloak_user_id, status, created_at)
                values (?, ?, ?, ?, ?, ?, 'sent', ?)
                """).params(id, tenantId, userId, email, role.name(), accountId, Timestamp.from(now)).update();
    }

    List<Invitation> invitations() {
        return jdbc.sql("""
                select id, user_account_id, email, role, status, created_at, accepted_at from invitation
                order by created_at desc
                """).query(INVITATION).list();
    }

    Optional<Invitation> invitationForUpdate(UUID id) {
        return jdbc.sql("""
                select id, user_account_id, email, role, status, created_at, accepted_at from invitation
                where id = ? for update
                """).param(id).query(INVITATION).optional();
    }

    Optional<String> invitationAccount(UUID id) {
        return jdbc.sql("select keycloak_user_id from invitation where id = ?").param(id).query(String.class)
                .optional();
    }

    Optional<String> accountOfUser(UUID userId) {
        return jdbc.sql("select keycloak_user_id from invitation where user_account_id = ?").param(userId)
                .query(String.class).optional();
    }

    /** True when an invitation to this e-mail was waiting. */
    boolean accept(String email, Instant now) {
        return jdbc.sql("""
                update invitation set status = 'accepted', accepted_at = ?
                where status = 'sent' and lower(email) = lower(?)
                """).params(Timestamp.from(now), email).update() > 0;
    }
}
