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
        return List.of("vendor", "contract", "subscription", "license_assignment");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.deleted("software.license_assignment", jdbc.sql("delete from license_assignment where employee_id = ?")
                .param(subject.employeeId()).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "software", "license_assignment", "subscription", "contract",
                "vendor");
    }
}
