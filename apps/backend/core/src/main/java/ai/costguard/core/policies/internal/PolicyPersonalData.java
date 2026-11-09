package ai.costguard.core.policies.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Company rules hold no personal data. Only erasing the company touches them (ADR 0021). */
@Component
class PolicyPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    PolicyPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("policy_rule", "policy_publication");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        if (jdbc.sql("select count(*) from policy_publication").query(Long.class).single() > 0) {
            report.tombstone("policy.snapshots.v1", tenantId.toString());
        }
        TenantRows.delete(jdbc, tenantId, report, "policies", "policy_rule", "policy_publication");
    }
}
