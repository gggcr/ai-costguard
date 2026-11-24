package ai.costguard.core.journal.internal;

import ai.costguard.core.shared.clickhouse.ClickHouse;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operation journal for the web client: every decision of the gateway from ClickHouse ({@code agent_operations},
 * ADR 0007), with the state of its receipt from PostgreSQL. Without ClickHouse it reads {@code agent_operation} in
 * PostgreSQL, which the core fills when the gateway posts to it directly (ADR 0080); with Kafka and no ClickHouse
 * that table stays empty, and so does the list.
 *
 * <p>TabSeparated writes NULL as {@code \N}. The SQL is built from the tenant of the token, a UUID and an instant parsed by Spring, never from free text.
 */
@Service
class OperationLog {

    static final int MAX_LIMIT = 500;
    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLICKHOUSE_READ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private final ClickHouse clickhouse;
    private final TenantSession session;
    private final JdbcClient jdbc;
    private final OperationRows postgres;

    OperationLog(ClickHouse clickhouse, TenantSession session, JdbcClient jdbc, OperationRows postgres) {
        this.clickhouse = clickhouse;
        this.session = session;
        this.jdbc = jdbc;
        this.postgres = postgres;
    }

    @Transactional(readOnly = true)
    List<OperationView> list(UUID tenantId, UUID agentId, Instant before, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<OperationView> found = new ArrayList<>();
        if (clickhouse.configured()) {
            List<String[]> rows;
            try {
                rows = clickhouse.rows(query(tenantId, agentId, before, bounded), "operations");
            } catch (ClickHouse.Unavailable e) {
                throw JournalProblem.OPERATIONS_UNAVAILABLE.failure();
            }
            for (String[] r : rows) {
                found.add(new OperationView(UUID.fromString(r[0]), UUID.fromString(r[1]), parseTime(r[2]), r[3],
                        r[4], nullable(r[5]), r[6], nullable(r[7]), new BigDecimal(r[8]), decimal(r[9]), r[10],
                        number(r[11]), number(r[12]), null));
            }
            session.bind(tenantId);
        } else {
            session.bind(tenantId);
            found.addAll(postgres.list(agentId, before, bounded));
        }
        if (found.isEmpty()) {
            return found;
        }
        Map<UUID, ReceiptStatus> receipts = receipts(found.stream().map(OperationView::id).toList());
        return found.stream().map(o -> o.withReceipt(receipts.get(o.id()))).toList();
    }

    static String query(UUID tenantId, UUID agentId, Instant before, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT toString(operation_id), toString(agent_id), toString(occurred_at), kind, provider, model,
                       decision, denial_code, toString(estimated_cost), toString(actual_cost), currency,
                       input_tokens, output_tokens
                FROM agent_operations FINAL
                WHERE tenant_id = toUUID('%s')""".formatted(tenantId));
        if (agentId != null) {
            sql.append(" AND agent_id = toUUID('").append(agentId).append("')");
        }
        if (before != null) {
            sql.append(" AND occurred_at < toDateTime64('").append(CLICKHOUSE_TIME.format(before))
                    .append("', 3, 'UTC')");
        }
        return sql.append(" ORDER BY occurred_at DESC LIMIT ").append(limit).toString();
    }

    private Map<UUID, ReceiptStatus> receipts(List<UUID> operations) {
        Map<UUID, ReceiptStatus> out = new HashMap<>();
        jdbc.sql("select operation_id, batch_id from receipt where operation_id = any(?)")
                .param(operations.toArray(UUID[]::new))
                .query((rs, n) -> out.put(rs.getObject("operation_id", UUID.class),
                        rs.getObject("batch_id") == null ? ReceiptStatus.pending : ReceiptStatus.anchored))
                .list();
        return out;
    }

    private static Instant parseTime(String value) {
        return LocalDateTime.parse(value, CLICKHOUSE_READ).toInstant(ZoneOffset.UTC);
    }

    private static String nullable(String value) {
        return "\\N".equals(value) ? null : value;
    }

    private static BigDecimal decimal(String value) {
        return "\\N".equals(value) ? null : new BigDecimal(value);
    }

    private static Long number(String value) {
        return "\\N".equals(value) ? null : Long.valueOf(value);
    }
}
