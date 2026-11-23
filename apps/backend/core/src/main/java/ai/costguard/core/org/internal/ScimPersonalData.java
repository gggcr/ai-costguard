package ai.costguard.core.org.internal;

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
 * The identity provider's name for a person (userName, often the e-mail) goes with the person; the SCIM tokens of a
 * company go with the company (ADR 0021, 0043). Runs before the org module erases the employee row.
 */
@Component
class ScimPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    ScimPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("scim_user", "scim_token");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.deleted("org.scim_user", jdbc.sql("delete from scim_user where employee_id = ?")
                .param(subject.employeeId()).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "org", "scim_user", "scim_token");
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("org.scim_user", jdbc.sql("select * from scim_user where employee_id = ?").param(subject.employeeId()).query().listOfRows());
    }
}
