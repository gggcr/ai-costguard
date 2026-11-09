package ai.costguard.core.journal.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Receipts are evidence and carry no personal data: tenant, operation, agent, amount, time. Erasing a person leaves
 * them as they are; the agent they name points at an owner whose row no longer names anyone. Erasing the company
 * deletes them. Their Merkle roots in NEAR stay: those carry neither the company nor a person (ADR 0006).
 */
@Component
class JournalPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    JournalPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("receipt", "receipt_chain", "anchor_batch");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.retained("journal.receipt", 0, "Квитанции не содержат персональных данных и не меняются; "
                + "связь с человеком обезличена через запись сотрудника", null);
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "journal", "receipt", "anchor_batch", "receipt_chain");
    }
}
