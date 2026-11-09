package ai.costguard.core.ledger.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Budgets and the ledger hold money, not people. Only erasing the company touches them (ADR 0021). */
@Component
class LedgerPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    LedgerPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("budget", "ledger_entry");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        for (UUID budget : jdbc.sql("select id from budget").query(UUID.class).list()) {
            report.tombstone("budgets.limits.v1", budget.toString());
        }
        TenantRows.delete(jdbc, tenantId, report, "ledger", "ledger_entry", "budget");
    }
}
