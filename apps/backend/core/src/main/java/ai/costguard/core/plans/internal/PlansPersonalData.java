package ai.costguard.core.plans.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Plans and payments hold nothing about a person; a company that leaves takes them along (ADR 0021). */
@Component
class PlansPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    PlansPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("tenant_plan", "plan_schedule", "payment", "payment_ref");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "plans", "payment_ref", "payment", "plan_schedule", "tenant_plan");
        report.tombstone(PlanService.TOPIC, tenantId.toString());
    }
}
