package ai.costguard.core.journal.internal;

import ai.costguard.core.journal.OperationJournal;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@code agent_operation} in PostgreSQL: the journal when ClickHouse is not there (ADR 0080). */
@Component
class OperationRows implements OperationJournal {

    private final JdbcClient jdbc;
    private final TenantSession session;

    OperationRows(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    @Override
    @Transactional
    public void record(Entry e) {
        session.bind(e.tenantId());
        jdbc.sql("""
                insert into agent_operation (tenant_id, operation_id, agent_id, occurred_at, kind, provider, model,
                                             decision, denial_code, estimated_cost, actual_cost, currency,
                                             input_tokens, output_tokens)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, operation_id) do update
                    set agent_id = excluded.agent_id, occurred_at = excluded.occurred_at, kind = excluded.kind,
                        provider = excluded.provider, model = excluded.model, decision = excluded.decision,
                        denial_code = excluded.denial_code, estimated_cost = excluded.estimated_cost,
                        actual_cost = excluded.actual_cost, currency = excluded.currency,
                        input_tokens = excluded.input_tokens, output_tokens = excluded.output_tokens
                    where agent_operation.occurred_at <= excluded.occurred_at""")
                .params(e.tenantId(), e.operationId(), e.agentId(), Timestamp.from(e.occurredAt()), e.kind(),
                        e.provider(), e.model(), e.decision(), e.denialCode(), e.estimatedCost(), e.actualCost(),
                        e.currency(), e.inputTokens(), e.outputTokens())
                .update();
    }

    /** The newest first, as the ClickHouse query of {@link OperationLog}; the caller has bound the tenant. */
    List<OperationView> list(UUID agentId, Instant before, int limit) {
        StringBuilder sql = new StringBuilder("select * from agent_operation where true");
        if (agentId != null) {
            sql.append(" and agent_id = :agent");
        }
        if (before != null) {
            sql.append(" and occurred_at < :before");
        }
        var query = jdbc.sql(sql.append(" order by occurred_at desc limit :limit").toString()).param("limit", limit);
        if (agentId != null) {
            query = query.param("agent", agentId);
        }
        if (before != null) {
            query = query.param("before", Timestamp.from(before));
        }
        return query.query((rs, n) -> view(rs)).list();
    }

    private static OperationView view(ResultSet rs) throws SQLException {
        return new OperationView(rs.getObject("operation_id", UUID.class), rs.getObject("agent_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("kind"), rs.getString("provider"),
                rs.getString("model"), rs.getString("decision"), rs.getString("denial_code"),
                rs.getBigDecimal("estimated_cost"), rs.getBigDecimal("actual_cost"), rs.getString("currency"),
                number(rs, "input_tokens"), number(rs, "output_tokens"), null);
    }

    private static Long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
