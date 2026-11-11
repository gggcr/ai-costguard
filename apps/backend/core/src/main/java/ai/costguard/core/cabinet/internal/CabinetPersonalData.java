package ai.costguard.core.cabinet.internal;

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
 * Employee documents are personnel records the employer must keep (ADR 0052): an erasure keeps them and reports
 * them as retained.
 */
@Component
class CabinetPersonalData implements DataHolder {

    private final JdbcClient jdbc;
    private final DocumentRepository documents;

    CabinetPersonalData(JdbcClient jdbc, DocumentRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    public List<String> tables() {
        return List.of("employee_document", "vacation_request", "payroll_payment", "payroll_schedule",
                "payroll_tenant");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        int requests = jdbc.sql("select count(*) from vacation_request where employee_id = ?")
                .param(subject.employeeId()).query(Integer.class).single();
        if (requests > 0) {
            report.retained("cabinet.vacation_request", requests,
                    "vacation records the employer keeps by law (ТК РФ, ст. 123, 125-ФЗ)", null);
        }
        int pay = jdbc.sql("select count(*) from payroll_payment where employee_id = ?")
                .param(subject.employeeId()).query(Integer.class).single();
        if (pay > 0) {
            report.retained("cabinet.payroll_payment", pay,
                    "pay records the employer keeps by law (402-ФЗ, ст. 29; НК РФ, ст. 230)", null);
        }
        int kept = documents.countOf(subject.employeeId());
        if (kept > 0) {
            report.retained("cabinet.employee_document", kept,
                    "personnel records the employer keeps by law (ТК РФ, 125-ФЗ)", null);
        }
    }

    /** The files go with the company's uploads: they share its prefix in the storage (imports erases it). */
    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "cabinet", "payroll_payment", "payroll_schedule",
                "vacation_request", "employee_document");
        jdbc.sql("delete from payroll_tenant where tenant_id = ?").param(tenantId).update();
    }
}
