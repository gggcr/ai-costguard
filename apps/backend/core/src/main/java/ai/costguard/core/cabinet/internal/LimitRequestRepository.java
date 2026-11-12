package ai.costguard.core.cabinet.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Заявки на продление лимита агента (ADR 0067). */
@Repository
class LimitRequestRepository {

    private static final String SELECT = """
            select r.id, r.employee_id, e.name as employee_name, r.agent_id, a.name as agent_name, r.budget_id,
                   r.current_limit, r.spent, r.add_amount, r.currency, r.reason, r.status, r.decided_by, r.decided_at,
                   r.decision_comment, r.created_at
            from limit_request r join employee e on e.id = r.employee_id
            left join agent a on a.id = r.agent_id""";

    private static final RowMapper<LimitRequest> ROW = (rs, i) -> {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new LimitRequest(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getObject("agent_id", UUID.class), rs.getString("agent_name"),
                rs.getObject("budget_id", UUID.class), rs.getBigDecimal("current_limit").stripTrailingZeros(),
                rs.getBigDecimal("spent").stripTrailingZeros(), rs.getBigDecimal("add_amount").stripTrailingZeros(),
                rs.getString("currency"), rs.getString("reason"), rs.getString("status"), rs.getString("decided_by"),
                decided == null ? null : decided.toInstant(), rs.getString("decision_comment"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    LimitRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, UUID employeeId, UUID agentId, UUID budgetId, BigDecimal currentLimit,
            BigDecimal spent, BigDecimal add, String currency, String reason, Instant at) {
        jdbc.sql("""
                insert into limit_request (id, tenant_id, employee_id, agent_id, budget_id, current_limit, spent,
                                           add_amount, currency, reason, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)""")
                .params(id, tenantId, employeeId, agentId, budgetId, currentLimit, spent, add, currency,
                        reason.strip(), Timestamp.from(at))
                .update();
    }

    Optional<LimitRequest> find(UUID id) {
        return jdbc.sql(SELECT + " where r.id = ?").param(id).query(ROW).optional();
    }

    Optional<LimitRequest> lock(UUID id) {
        return jdbc.sql(SELECT + " where r.id = ? for update of r").param(id).query(ROW).optional();
    }

    boolean pendingFor(UUID agentId) {
        return jdbc.sql("select count(*) from limit_request where agent_id = ? and status = 'pending'")
                .param(agentId).query(Integer.class).single() > 0;
    }

    List<LimitRequest> of(UUID employeeId) {
        return jdbc.sql(SELECT + " where r.employee_id = ? order by r.created_at desc limit 100").param(employeeId)
                .query(ROW).list();
    }

    List<LimitRequest> ofEmployees(Collection<UUID> employees) {
        if (employees.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where r.employee_id in (:ids) order by (r.status = 'pending') desc, r.created_at desc"
                + " limit 500").param("ids", employees).query(ROW).list();
    }

    List<LimitRequest> all() {
        return jdbc.sql(SELECT + " order by (r.status = 'pending') desc, r.created_at desc limit 500").query(ROW)
                .list();
    }

    void decide(UUID id, String status, String by, Instant at, String comment) {
        jdbc.sql("""
                update limit_request set status = ?, decided_by = ?, decided_at = ?, decision_comment = ?
                where id = ?""")
                .params(status, by, Timestamp.from(at), comment == null || comment.isBlank() ? null : comment.strip(),
                        id)
                .update();
    }
}
