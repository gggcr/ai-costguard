package ai.costguard.core.plans.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * tenant_plan and payment are scoped by RLS to the tenant bound to the transaction; plan_schedule and payment_ref
 * hold ids and dates only and are read across companies by the watcher and by provider notices.
 */
@Repository
class PlanRepository {

    private static final RowMapper<PlanState> STATE = (rs, n) -> new PlanState(rs.getObject("tenant_id", UUID.class),
            rs.getString("plan"), instant(rs, "trial_ends_at"), instant(rs, "access_until"), rs.getLong("version"));

    private static final RowMapper<PaymentRow> PAYMENT = (rs, n) -> new PaymentRow(rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class), rs.getString("plan"), rs.getBigDecimal("amount"),
            rs.getString("currency"), rs.getString("provider"), rs.getString("provider_payment_id"),
            rs.getString("status"));

    private static final RowMapper<ScheduleRow> SCHEDULE = (rs, n) -> new ScheduleRow(
            rs.getObject("tenant_id", UUID.class), instant(rs, "access_until"), instant(rs, "warned_for"),
            instant(rs, "ended_for"));

    private final JdbcClient jdbc;

    PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    Optional<PlanState> state() {
        return jdbc.sql("select tenant_id, plan, trial_ends_at, access_until, version from tenant_plan")
                .query(STATE).optional();
    }

    Optional<PlanState> stateForUpdate() {
        return jdbc.sql("select tenant_id, plan, trial_ends_at, access_until, version from tenant_plan for update")
                .query(STATE).optional();
    }

    void insert(UUID tenantId, String plan, Instant trialEndsAt, Instant accessUntil, Instant now) {
        jdbc.sql("""
                insert into tenant_plan (tenant_id, plan, trial_ends_at, access_until, version, updated_at)
                values (?, ?, ?, ?, 1, ?)
                """).params(tenantId, plan, ts(trialEndsAt), ts(accessUntil), ts(now)).update();
    }

    /** Returns the new version. */
    long update(String plan, Instant accessUntil, Instant now) {
        return jdbc.sql("""
                update tenant_plan set plan = ?, access_until = ?, version = version + 1, updated_at = ?
                returning version
                """).params(plan, ts(accessUntil), ts(now)).query(Long.class).single();
    }

    long touch(Instant now) {
        return jdbc.sql("update tenant_plan set version = version + 1, updated_at = ? returning version")
                .param(ts(now)).query(Long.class).single();
    }

    /** A new period is watched afresh: what was warned about or ended belongs to the old one. */
    void schedule(UUID tenantId, Instant accessUntil) {
        jdbc.sql("""
                insert into plan_schedule (tenant_id, access_until) values (?, ?)
                on conflict (tenant_id) do update set access_until = excluded.access_until, warned_for = null,
                    ended_for = null
                """).params(tenantId, ts(accessUntil)).update();
    }

    void forget(UUID tenantId) {
        jdbc.sql("delete from plan_schedule where tenant_id = ?").param(tenantId).update();
        jdbc.sql("delete from payment_ref where tenant_id = ?").param(tenantId).update();
        jdbc.sql("delete from payment").update();
        jdbc.sql("delete from tenant_plan").update();
    }

    /** Companies whose period ends within {@code warnBefore} and was not warned about, or ended and was not closed. */
    List<UUID> due(Instant now, Instant warnFrom, int limit) {
        return jdbc.sql("""
                select tenant_id from plan_schedule
                where access_until is not null
                  and ((access_until <= ? and warned_for is distinct from access_until)
                       or (access_until <= ? and ended_for is distinct from access_until))
                order by access_until
                limit ?
                """).params(ts(warnFrom), ts(now), limit).query(UUID.class).list();
    }

    /** Another instance holding the row has it: empty, and that one handles it. */
    Optional<ScheduleRow> lockSchedule(UUID tenantId) {
        return jdbc.sql("""
                select tenant_id, access_until, warned_for, ended_for from plan_schedule
                where tenant_id = ? for update skip locked
                """).param(tenantId).query(SCHEDULE).optional();
    }

    void warned(UUID tenantId, Instant accessUntil) {
        jdbc.sql("update plan_schedule set warned_for = ? where tenant_id = ?").params(ts(accessUntil), tenantId)
                .update();
    }

    /** An ended period was warned about too: a warning after the end would only confuse. */
    void ended(UUID tenantId, Instant accessUntil) {
        jdbc.sql("update plan_schedule set ended_for = ?, warned_for = ? where tenant_id = ?")
                .params(ts(accessUntil), ts(accessUntil), tenantId).update();
    }

    void insertPayment(UUID id, UUID tenantId, String plan, BigDecimal amount, String currency, String provider,
            Instant now) {
        jdbc.sql("""
                insert into payment (id, tenant_id, plan, amount, currency, provider, status, created_at)
                values (?, ?, ?, ?, ?, ?, 'pending', ?)
                """).params(id, tenantId, plan, amount, currency, provider, ts(now)).update();
    }

    Optional<PaymentRow> paymentForUpdate(UUID id) {
        return jdbc.sql("""
                select id, tenant_id, plan, amount, currency, provider, provider_payment_id, status
                from payment where id = ? for update
                """).param(id).query(PAYMENT).optional();
    }

    void started(UUID id, UUID tenantId, String provider, String providerPaymentId) {
        jdbc.sql("update payment set provider_payment_id = ? where id = ?").params(providerPaymentId, id).update();
        jdbc.sql("""
                insert into payment_ref (provider, provider_payment_id, tenant_id, payment_id) values (?, ?, ?, ?)
                on conflict do nothing
                """).params(provider, providerPaymentId, tenantId, id).update();
    }

    void status(UUID id, String status, Instant paidAt) {
        jdbc.sql("update payment set status = ?, paid_at = ? where id = ?").params(status, ts(paidAt), id).update();
    }

    Optional<PaymentRef> ref(String provider, String providerPaymentId) {
        return jdbc.sql("select tenant_id, payment_id from payment_ref where provider = ? and provider_payment_id = ?")
                .params(provider, providerPaymentId)
                .query((rs, n) -> new PaymentRef(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class)))
                .optional();
    }
}
