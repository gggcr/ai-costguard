package ai.costguard.core.ledger.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class LedgerRepository {

    private static final String SELECT = """
            select b.id, b.name, b.level, b.ref_id, b.parent_id, b.limit_amount, b.currency, b.version,
                   coalesce((select sum(e.amount) from ledger_entry e where e.budget_id = b.id), 0) as spent
            from budget b
            """;

    private static final RowMapper<Budget> BUDGET = (rs, n) -> new Budget(rs.getObject("id", UUID.class),
            rs.getString("name"), BudgetLevel.valueOf(rs.getString("level")), rs.getObject("ref_id", UUID.class),
            rs.getObject("parent_id", UUID.class), rs.getBigDecimal("limit_amount").stripTrailingZeros(),
            rs.getString("currency"), rs.getBigDecimal("spent").stripTrailingZeros(), rs.getLong("version"));

    private static final RowMapper<LedgerEntry> ENTRY = (rs, n) -> new LedgerEntry(rs.getObject("id", UUID.class),
            rs.getObject("budget_id", UUID.class), rs.getObject("operation_id", UUID.class),
            EntryKind.valueOf(rs.getString("kind")), rs.getBigDecimal("amount").stripTrailingZeros(),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Budget> budgets() {
        return jdbc.sql(SELECT + " order by b.level, b.name, b.id").query(BUDGET).list();
    }

    Optional<Budget> budget(UUID id) {
        return jdbc.sql(SELECT + " where b.id = ?").param(id).query(BUDGET).optional();
    }

    /** Budget id -> what its entries charged before {@code asOf}: reversals count against their charges. */
    java.util.Map<UUID, BigDecimal> spentBefore(Instant asOf) {
        java.util.Map<UUID, BigDecimal> out = new java.util.HashMap<>();
        jdbc.sql("""
                select b.id, coalesce(sum(e.amount), 0) as spent from budget b
                left join ledger_entry e on e.budget_id = b.id and e.occurred_at < ?
                group by b.id
                """).param(Timestamp.from(asOf))
                .query((rs, n) -> out.put(rs.getObject("id", UUID.class), rs.getBigDecimal("spent")))
                .list();
        return out;
    }

    Optional<Budget> budgetOfAgent(UUID agentId) {
        return jdbc.sql(SELECT + " where b.level = 'agent' and b.ref_id = ?").param(agentId).query(BUDGET).optional();
    }

    void insert(UUID tenantId, UUID id, NewBudget b) {
        jdbc.sql("""
                insert into budget (id, tenant_id, name, level, ref_id, parent_id, limit_amount, currency)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, b.name(), b.level().name(), b.refId(), b.parentId(), b.limit(), b.currency())
                .update();
    }

    /** Returns false if there is no such budget. The version moves with the limit, so events stay ordered. */
    boolean setLimit(UUID id, BigDecimal limit) {
        return jdbc.sql("update budget set limit_amount = ?, version = version + 1 where id = ?")
                .params(limit, id).update() == 1;
    }

    /** Returns false if this entry was already there. */
    boolean append(UUID tenantId, UUID budgetId, UUID operationId, EntryKind kind, BigDecimal amount, Instant at) {
        return jdbc.sql("""
                insert into ledger_entry (id, tenant_id, budget_id, operation_id, kind, amount, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (budget_id, operation_id, kind) do nothing
                """).params(UUID.randomUUID(), tenantId, budgetId, operationId, kind.name(), amount, Timestamp.from(at))
                .update() == 1;
    }

    List<LedgerEntry> charges(UUID operationId) {
        return jdbc.sql("""
                select id, budget_id, operation_id, kind, amount, occurred_at from ledger_entry
                where operation_id = ? and kind = 'charge'
                """).param(operationId).query(ENTRY).list();
    }

    List<ai.costguard.core.ledger.SpendSummary.Month> companySpendByMonth(java.time.LocalDate from,
            java.time.LocalDate to) {
        return jdbc.sql("""
                select to_char(date_trunc('month', e.occurred_at at time zone 'UTC'), 'YYYY-MM') as month,
                       sum(e.amount) as amount
                from ledger_entry e join budget b on b.id = e.budget_id
                where b.level = 'company' and e.occurred_at >= ? and e.occurred_at < ?
                group by 1 order by 1
                """).params(Timestamp.from(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .query((rs, n) -> new ai.costguard.core.ledger.SpendSummary.Month(
                        java.time.YearMonth.parse(rs.getString("month")), rs.getBigDecimal("amount").stripTrailingZeros()))
                .list();
    }

    List<LedgerEntry> entries(UUID budgetId, int page, int size) {
        return jdbc.sql("""
                select id, budget_id, operation_id, kind, amount, occurred_at from ledger_entry
                where budget_id = ? order by occurred_at desc, id limit ? offset ?
                """).params(budgetId, size, (long) page * size).query(ENTRY).list();
    }
}
