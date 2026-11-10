package ai.costguard.core.notifications.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Settings, deliveries and Telegram linking. Settings and deliveries are under RLS; notification_tenant,
 * telegram_link and telegram_state are not (see V23).
 */
@Repository
class DeliveryRepository {

    /** Instances poll Telegram one at a time: whoever holds this transaction lock. */
    private static final long TELEGRAM_LOCK = 0x436f73744775L;

    private static final RowMapper<Settings> SETTINGS = (rs, n) -> new Settings(rs.getObject("user_id", UUID.class),
            Threshold.valueOf(rs.getString("email_level")), Threshold.valueOf(rs.getString("telegram_level")),
            rs.getObject("telegram_chat_id", Long.class),
            rs.getTimestamp("telegram_linked_at") == null ? null : rs.getTimestamp("telegram_linked_at").toInstant());

    private final JdbcClient jdbc;

    DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Settings> settings(UUID userId) {
        return jdbc.sql("""
                select user_id, email_level, telegram_level, telegram_chat_id, telegram_linked_at
                from notification_setting where user_id = ?""").param(userId).query(SETTINGS).optional();
    }

    void saveLevels(UUID tenantId, UUID userId, Threshold email, Threshold telegram) {
        jdbc.sql("""
                insert into notification_setting (user_id, tenant_id, email_level, telegram_level) values (?, ?, ?, ?)
                on conflict (user_id) do update set email_level = excluded.email_level,
                                                     telegram_level = excluded.telegram_level""")
                .params(userId, tenantId, email.name(), telegram.name()).update();
    }

    void linkTelegram(UUID tenantId, UUID userId, long chatId, Instant at) {
        jdbc.sql("""
                insert into notification_setting (user_id, tenant_id, telegram_chat_id, telegram_linked_at)
                values (?, ?, ?, ?)
                on conflict (user_id) do update set telegram_chat_id = excluded.telegram_chat_id,
                                                     telegram_linked_at = excluded.telegram_linked_at""")
                .params(userId, tenantId, chatId, Timestamp.from(at)).update();
    }

    void unlinkTelegram(UUID userId) {
        jdbc.sql("update notification_setting set telegram_chat_id = null, telegram_linked_at = null where user_id = ?")
                .param(userId).update();
    }

    void insert(UUID tenantId, UUID notificationId, UUID userId, String channel, Instant at) {
        jdbc.sql("""
                insert into notification_delivery (id, tenant_id, notification_id, user_id, channel, next_attempt_at)
                values (?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), tenantId, notificationId, userId, channel, Timestamp.from(at)).update();
        jdbc.sql("insert into notification_tenant (tenant_id) values (?) on conflict do nothing").param(tenantId)
                .update();
    }

    List<UUID> tenants() {
        return jdbc.sql("select tenant_id from notification_tenant").query(UUID.class).list();
    }

    /** Due deliveries of the bound tenant, locked: another instance skips them. */
    List<Delivery> due(Instant now, int limit) {
        return jdbc.sql("""
                select d.id, d.notification_id, d.user_id, d.channel, d.attempts, n.text, n.link, n.level
                from notification_delivery d join notification n on n.id = d.notification_id
                where d.status = 'pending' and d.next_attempt_at <= ?
                order by d.next_attempt_at limit ?
                for update of d skip locked""")
                .params(Timestamp.from(now), limit)
                .query((rs, n) -> new Delivery(rs.getObject("id", UUID.class),
                        rs.getObject("notification_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("channel"), rs.getInt("attempts"), rs.getString("text"), rs.getString("link"),
                        Level.valueOf(rs.getString("level"))))
                .list();
    }

    void sent(UUID id, Instant at) {
        jdbc.sql("update notification_delivery set status = 'sent', attempts = attempts + 1, sent_at = ?, "
                + "last_error = null where id = ?").params(Timestamp.from(at), id).update();
    }

    void retry(UUID id, Instant next, String error) {
        jdbc.sql("update notification_delivery set attempts = attempts + 1, next_attempt_at = ?, last_error = ? "
                + "where id = ?").params(Timestamp.from(next), error, id).update();
    }

    void failed(UUID id, String error) {
        jdbc.sql("update notification_delivery set status = 'failed', attempts = attempts + 1, last_error = ? "
                + "where id = ?").params(error, id).update();
    }

    record DeliveryState(String status, int attempts, String lastError) {
    }

    /** For tests and support: what happened to the deliveries of one notification, by user and channel. */
    List<DeliveryState> states(UUID notificationId) {
        return jdbc.sql("select status, attempts, last_error from notification_delivery where notification_id = ?")
                .param(notificationId)
                .query((rs, n) -> new DeliveryState(rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("last_error")))
                .list();
    }

    void saveLink(String codeHash, UUID tenantId, UUID userId, Instant expiresAt) {
        jdbc.sql("delete from telegram_link where user_id = ?").param(userId).update();
        jdbc.sql("insert into telegram_link (code_hash, tenant_id, user_id, expires_at) values (?, ?, ?, ?)")
                .params(codeHash, tenantId, userId, Timestamp.from(expiresAt)).update();
    }

    record LinkTarget(UUID tenantId, UUID userId, Instant expiresAt) {
    }

    /** The code works once: it is deleted whether or not it has expired. */
    Optional<LinkTarget> takeLink(String codeHash) {
        return jdbc.sql("delete from telegram_link where code_hash = ? returning tenant_id, user_id, expires_at")
                .param(codeHash)
                .query((rs, n) -> new LinkTarget(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    void forgetExpiredLinks(Instant now) {
        jdbc.sql("delete from telegram_link where expires_at <= ?").param(Timestamp.from(now)).update();
    }

    boolean lockTelegram() {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(?)").param(TELEGRAM_LOCK)
                .query(Boolean.class).single());
    }

    long offset() {
        return jdbc.sql("select update_offset from telegram_state where id = 1").query(Long.class).single();
    }

    void offset(long next) {
        jdbc.sql("update telegram_state set update_offset = ? where id = 1").param(next).update();
    }
}
