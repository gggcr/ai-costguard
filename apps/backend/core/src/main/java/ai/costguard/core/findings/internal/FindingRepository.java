package ai.costguard.core.findings.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FindingRepository {

    private final JdbcClient jdbc;

    FindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private List<FindingEvent> history(UUID id) {
        return jdbc.sql("select at, by_whom, text from finding_event where finding_id = ? order by at, id").param(id)
                .query((rs, n) -> new FindingEvent(rs.getTimestamp("at").toInstant(), rs.getString("by_whom"),
                        rs.getString("text")))
                .list();
    }

    private List<Finding> query(String where, Object... params) {
        List<Finding> bare = jdbc.sql("select * from finding " + where).params(params)
                .query((rs, n) -> new Finding(rs.getObject("id", UUID.class), rs.getString("rule"),
                        rs.getString("fingerprint"), rs.getString("title"), rs.getString("description"),
                        rs.getObject("subscription_id", UUID.class), rs.getObject("agent_id", UUID.class),
                        rs.getBigDecimal("expected_savings"), rs.getString("status"),
                        rs.getObject("owner_id", UUID.class), rs.getObject("postponed_until", LocalDate.class),
                        rs.getString("reject_reason"), rs.getInt("missed_invoices"),
                        rs.getTimestamp("created_at").toInstant(), List.of()))
                .list();
        return bare.stream().map(f -> new Finding(f.id(), f.rule(), f.fingerprint(), f.title(), f.description(),
                f.subscriptionId(), f.agentId(), f.expectedSavings(), f.status(), f.ownerId(), f.postponedUntil(),
                f.rejectReason(), f.missedInvoices(), f.createdAt(), history(f.id()))).toList();
    }

    List<Finding> list(String status) {
        return status == null ? query("order by created_at desc, id")
                : query("where status = ? order by created_at desc, id", status);
    }

    Optional<Finding> find(UUID id) {
        return query("where id = ?", id).stream().findFirst();
    }

    Optional<Finding> lock(UUID id) {
        return query("where id = ? for update", id).stream().findFirst();
    }

    Optional<Finding> lockByFingerprint(String fingerprint) {
        return query("where fingerprint = ? for update", fingerprint).stream().findFirst();
    }

    /** Returns false if another call created it first; the caller then reads it by fingerprint. */
    boolean insert(UUID tenantId, UUID id, String fingerprint, String rule, String title, String description,
            BigDecimal savings, UUID subscriptionId, UUID agentId, Instant now) {
        return jdbc.sql("""
                insert into finding (id, tenant_id, fingerprint, rule, title, description, subscription_id, agent_id,
                                     expected_savings, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'new', ?)
                on conflict (tenant_id, fingerprint) do nothing
                """).params(id, tenantId, fingerprint, rule, title, description, subscriptionId, agentId, savings,
                Timestamp.from(now)).update() == 1;
    }

    /** Fingerprints of the rule's findings that are not closed, so the engine can close what it no longer sees. */
    List<String> openFingerprints(String rule) {
        return jdbc.sql("select fingerprint from finding where rule = ? and status not in ('confirmed', 'rejected', "
                + "'auto_closed')").param(rule).query(String.class).list();
    }

    void refresh(UUID id, String title, String description, BigDecimal savings) {
        jdbc.sql("update finding set title = ?, description = ?, expected_savings = ? where id = ?")
                .params(title, description, savings, id).update();
    }

    void update(UUID id, String status, UUID ownerId, LocalDate postponedUntil, String rejectReason, int missed) {
        jdbc.sql("""
                update finding set status = ?, owner_id = ?, postponed_until = ?, reject_reason = ?,
                                   missed_invoices = ?
                where id = ?
                """).params(status, ownerId, postponedUntil, rejectReason, missed, id).update();
    }

    void log(UUID tenantId, UUID findingId, Instant at, String by, String text) {
        jdbc.sql("insert into finding_event (id, tenant_id, finding_id, at, by_whom, text) values (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenantId, findingId, Timestamp.from(at), by, text).update();
    }

    void scheduleWakeup(UUID findingId, UUID tenantId, LocalDate on) {
        jdbc.sql("""
                insert into finding_wakeup (finding_id, tenant_id, wake_on) values (?, ?, ?)
                on conflict (finding_id) do update set wake_on = excluded.wake_on
                """).params(findingId, tenantId, on).update();
    }

    void removeWakeup(UUID findingId) {
        jdbc.sql("delete from finding_wakeup where finding_id = ?").param(findingId).update();
    }

    List<DueFinding> dueWakeups(LocalDate today) {
        return jdbc.sql("select finding_id, tenant_id from finding_wakeup where wake_on <= ? limit 500").param(today)
                .query((rs, n) -> new DueFinding(rs.getObject("finding_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class)))
                .list();
    }

    FindingTotals totals() {
        return jdbc.sql("""
                select count(*) filter (where status in ('new', 'review', 'accepted', 'in_progress', 'postponed')) as open,
                       coalesce(sum(expected_savings) filter (where status in ('new', 'review', 'accepted',
                                                                               'in_progress', 'postponed')), 0) as expected,
                       coalesce(sum(expected_savings) filter (where status = 'confirmed'), 0) as confirmed
                from finding
                """).query((rs, n) -> new FindingTotals(rs.getInt("open"), rs.getBigDecimal("expected"),
                rs.getBigDecimal("confirmed"))).single();
    }
}
