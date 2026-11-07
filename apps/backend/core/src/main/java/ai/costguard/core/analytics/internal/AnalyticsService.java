package ai.costguard.core.analytics.internal;

import ai.costguard.core.findings.FindingsSummary;
import ai.costguard.core.ledger.SpendSummary;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard summary from what the core holds itself. Software costs from invoices, forecasts and reports come
 * from ClickHouse and the forecast service, which are not there yet.
 */
@Service
class AnalyticsService {

    private final SpendSummary spend;
    private final FindingsSummary findings;
    private final TenantSession session;
    private final Clock clock;

    AnalyticsService(SpendSummary spend, FindingsSummary findings, TenantSession session, Clock clock) {
        this.spend = spend;
        this.findings = findings;
        this.session = session;
        this.clock = clock;
    }

    record Summary(List<SpendSummary.Month> agentSpend, FindingsSummary.Totals findings) {
    }

    @Transactional(readOnly = true)
    Summary summary(UUID tenantId, int months) {
        session.bind(tenantId);
        YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return new Summary(spend.agentSpendByMonth(now.minusMonths(months - 1L), now), findings.totals());
    }
}
