package ai.costguard.core.ledger.internal;

import ai.costguard.core.ledger.BudgetLimitChanged;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The counters of the gateway come back from the ledger (board, «после сбоя счётчики восстанавливаются из журнала
 * проводок»; ADR 0040). Every {@code costguard.ledger.counter-snapshot-interval} (5 min) every budget goes to
 * budgets.limits.v1 again with what its postings charged up to a moment {@code counter-snapshot-lag} (2 min) ago: the
 * operations the gateway settled before then have reached the ledger by now. The gateway sets its counter to that
 * plus what it settled itself after the moment, so a counter lost with Redis is right again within one interval,
 * and one that was right stays right. The limit and its version are the current ones: the snapshot changes no limit.
 */
@Component
class CounterSnapshot {

    private static final Logger log = LoggerFactory.getLogger(CounterSnapshot.class);

    private final LedgerRepository ledger;
    private final TenantSession session;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration lag;
    private final TransactionTemplate tx;

    CounterSnapshot(LedgerRepository ledger, TenantSession session, JdbcClient jdbc, ApplicationEventPublisher events,
            Clock clock, @Value("${costguard.ledger.counter-snapshot-lag:PT2M}") Duration lag,
            PlatformTransactionManager transactions) {
        this.ledger = ledger;
        this.session = session;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
        this.lag = lag;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${costguard.ledger.counter-snapshot-interval:PT5M}",
            initialDelayString = "${costguard.ledger.counter-snapshot-interval:PT5M}")
    void everyTenant() {
        List<UUID> tenants = jdbc.sql("select tenant_id from ledger_tenant").query(UUID.class).list();
        Instant asOf = clock.instant().minus(lag).truncatedTo(ChronoUnit.MILLIS);
        int sent = 0;
        for (UUID tenant : tenants) {
            try {
                Integer n = tx.execute(status -> snapshot(tenant, asOf));
                sent += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("Counter snapshot of tenant {} failed", tenant, e);
            }
        }
        log.debug("Counter snapshot of {} budgets in {} tenants as of {}", sent, tenants.size(), asOf);
    }

    /** Runs inside a transaction; returns how many budgets it sent. */
    int snapshot(UUID tenantId, Instant asOf) {
        session.bind(tenantId);
        Map<UUID, BigDecimal> spent = ledger.spentBefore(asOf);
        List<Budget> budgets = ledger.budgets();
        for (Budget b : budgets) {
            events.publishEvent(new BudgetLimitChanged(tenantId, b.id(), b.parentId(), b.level().name(), b.refId(),
                    b.limit(), b.currency(), b.version(), spent.getOrDefault(b.id(), BigDecimal.ZERO), asOf));
        }
        return budgets.size();
    }
}
