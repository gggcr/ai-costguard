package ai.costguard.core.analytics.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Forecasts are sums of a company's spend by department: nothing about a person (ADR 0021). */
@Component
class AnalyticsPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    AnalyticsPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("forecast");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "analytics", "forecast");
    }
}
