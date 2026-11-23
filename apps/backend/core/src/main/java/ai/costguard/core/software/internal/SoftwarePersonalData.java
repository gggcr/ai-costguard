package ai.costguard.core.software.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A person's licenses, and their last sign-in to each service, go with the person (ADR 0021). */
@Component
class SoftwarePersonalData implements DataHolder {

    private final JdbcClient jdbc;

    SoftwarePersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("vendor", "contract", "subscription", "license_assignment", "product_plan", "invoice",
                "invoice_line", "payment", "product", "activity_event");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.deleted("software.license_assignment", jdbc.sql("delete from license_assignment where employee_id = ?")
                .param(subject.employeeId()).update());
        report.deleted("software.activity_event", jdbc.sql("delete from activity_event where employee_id = ?")
                .param(subject.employeeId()).update());
    }

    /** Sign-ins are kept as long as the activity they explain (PRIVACY_RETAIN_ACTIVITY, ADR 0042). */
    @Override
    public void expire(Retention retention, Report report) {
        report.deleted("software.activity_event", jdbc.sql("delete from activity_event where occurred_at < ?")
                .param(java.sql.Timestamp.from(retention.since(retention.activity()))).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "software", "activity_event", "license_assignment", "payment",
                "invoice_line", "invoice", "subscription", "product", "product_plan", "contract", "vendor");
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("software.license_assignment", jdbc.sql("select * from license_assignment where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("software.activity_event", jdbc.sql("select * from activity_event where employee_id = ?").param(subject.employeeId()).query().listOfRows());
    }
}
