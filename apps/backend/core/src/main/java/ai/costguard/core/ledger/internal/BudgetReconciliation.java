package ai.costguard.core.ledger.internal;

import ai.costguard.core.shared.clickhouse.ClickHouse;
import ai.costguard.platform.tenant.TenantSession;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that what the gateway charged is what the ledger holds (ADR 0026). The gateway reports every operation to
 * {@code agent.operations.v1}; ClickHouse keeps all of them, the core posts the allowed ones with an actual cost to
 * the agent's budget. Over a window that ended an hour ago, so late deliveries have arrived, the two sums must match
 * per company and currency. They do not when an agent spends without a budget, a posting failed, or the outbox lost
 * an operation; the alert {@code BudgetReconciliationMismatch} then points at docs/runbooks/budgets.md.
 *
 * <p>Off while ClickHouse is not configured. Metrics: {@code costguard_budget_reconciliation_mismatches} (pairs of
 * company and currency that differ), {@code costguard_budget_reconciliation_max_difference} and
 * {@code costguard_budget_reconciliation_last_success_timestamp_seconds}. No tenant label: ids go to the log.
 */
@Component
class BudgetReconciliation {

    private static final Logger log = LoggerFactory.getLogger(BudgetReconciliation.class);
    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    record Mismatch(UUID tenantId, String currency, BigDecimal gateway, BigDecimal ledger) {
        BigDecimal difference() {
            return gateway.subtract(ledger).abs();
        }
    }

    private final ClickHouse clickhouse;
    private final TenantSession session;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BigDecimal tolerance;
    private final Duration window;
    private final Duration settle;
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicReference<Double> maxDifference = new AtomicReference<>(0.0);
    private final AtomicLong lastSuccess = new AtomicLong();

    BudgetReconciliation(ClickHouse clickhouse, TenantSession session, JdbcClient jdbc,
            PlatformTransactionManager transactions, Clock clock, MeterRegistry registry,
            @Value("${costguard.ledger.reconciliation.tolerance:0.01}") BigDecimal tolerance,
            @Value("${costguard.ledger.reconciliation.window:PT24H}") Duration window,
            @Value("${costguard.ledger.reconciliation.settle:PT1H}") Duration settle) {
        this.clickhouse = clickhouse;
        this.session = session;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.tolerance = tolerance;
        this.window = window;
        this.settle = settle;
        if (clickhouse.configured()) {
            Gauge.builder("costguard.budget.reconciliation.mismatches", mismatches, AtomicLong::get)
                    .description("Pairs of company and currency where gateway and ledger disagree").register(registry);
            Gauge.builder("costguard.budget.reconciliation.max.difference", maxDifference, AtomicReference::get)
                    .description("The largest disagreement, in the currency of the budget").register(registry);
            Gauge.builder("costguard.budget.reconciliation.last.success.timestamp.seconds", lastSuccess,
                    AtomicLong::get).baseUnit("seconds").description("When the last reconciliation finished")
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${costguard.ledger.reconciliation.every:PT1H}",
            initialDelayString = "${costguard.ledger.reconciliation.initial-delay:PT5M}")
    void hourly() {
        if (!clickhouse.configured()) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Budget reconciliation failed, the next round tries again: {}", e.getMessage());
        }
    }

    /** One round. Each company is read in a transaction of its own: RLS shows one company at a time. */
    List<Mismatch> reconcile() {
        Instant to = clock.instant().minus(settle);
        Instant from = to.minus(window);
        Map<UUID, Map<String, BigDecimal>> gateway = new HashMap<>();
        for (String[] row : clickhouse.rows("""
                SELECT tenant_id, currency, toString(sum(actual_cost)) FROM agent_operations FINAL
                WHERE decision = 'ALLOWED' AND actual_cost IS NOT NULL
                  AND occurred_at >= toDateTime64('%s', 3, 'UTC') AND occurred_at < toDateTime64('%s', 3, 'UTC')
                GROUP BY tenant_id, currency""".formatted(CLICKHOUSE_TIME.format(from), CLICKHOUSE_TIME.format(to)),
                "reconciliation")) {
            gateway.computeIfAbsent(UUID.fromString(row[0]), t -> new HashMap<>())
                    .merge(row[1], new BigDecimal(row[2]), BigDecimal::add);
        }
        List<Mismatch> found = new ArrayList<>();
        for (var tenant : gateway.entrySet()) {
            Map<String, BigDecimal> ledger = tx.execute(status -> ledgerOf(tenant.getKey(), from, to));
            for (var spent : tenant.getValue().entrySet()) {
                BigDecimal posted = ledger == null ? BigDecimal.ZERO
                        : ledger.getOrDefault(spent.getKey(), BigDecimal.ZERO);
                Mismatch m = new Mismatch(tenant.getKey(), spent.getKey(), spent.getValue(), posted);
                if (m.difference().compareTo(tolerance) > 0) {
                    found.add(m);
                    log.warn("Budget reconciliation: tenant {} {} gateway {} ledger {} from {} to {}", m.tenantId(),
                            m.currency(), m.gateway().toPlainString(), m.ledger().toPlainString(), from, to);
                }
            }
        }
        mismatches.set(found.size());
        maxDifference.set(found.stream().map(Mismatch::difference).reduce(BigDecimal.ZERO, BigDecimal::max)
                .doubleValue());
        lastSuccess.set(clock.instant().getEpochSecond());
        return found;
    }

    /** Charges on agent budgets only: an operation is posted on every level, and the agent's is the first. */
    private Map<String, BigDecimal> ledgerOf(UUID tenantId, Instant from, Instant to) {
        session.bind(tenantId);
        Map<String, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                select b.currency, coalesce(sum(e.amount), 0) as total
                from ledger_entry e join budget b on b.id = e.budget_id
                where b.level = 'agent' and e.kind = 'charge' and e.occurred_at >= ? and e.occurred_at < ?
                group by b.currency
                """).params(Timestamp.from(from), Timestamp.from(to))
                .query(rs -> {
                    out.put(rs.getString("currency").strip(), rs.getBigDecimal("total"));
                });
        return out;
    }
}
