package ai.costguard.core.approvals.internal;

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
 * An approval keeps who decided it, by e-mail. Erasing the person puts a pseudonym there instead, the same for all
 * their decisions, so the record still shows that one person decided them. The approver hash in NEAR was computed
 * from the e-mail with the tenant and approval ids (ADR 0009); with the e-mail gone from here nobody can tie it to
 * the person.
 */
@Component
class ApprovalPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    ApprovalPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("approval_request", "approval_timer");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.anonymized("approvals.approval_request.decided_by", jdbc.sql("""
                update approval_request set decided_by = :pseudonym where lower(decided_by) in (:emails)
                """).param("pseudonym", subject.pseudonym()).param("emails", subject.emails()).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        for (UUID operation : jdbc.sql("select operation_id from approval_request where status <> 'pending'")
                .query(UUID.class).list()) {
            report.tombstone("approvals.decisions.v1", operation.toString());
        }
        TenantRows.delete(jdbc, tenantId, report, "approvals", "approval_timer", "approval_request");
    }
}
