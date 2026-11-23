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
    private final PurchaseRepository purchases;
    private final ai.costguard.core.imports.StoredFiles files;

    private ShadowService shadow;

    @org.springframework.beans.factory.annotation.Autowired
    void shadow(ShadowService shadow) {
        this.shadow = shadow;
    }

    CabinetPersonalData(JdbcClient jdbc, DocumentRepository documents, PurchaseRepository purchases,
            ai.costguard.core.imports.StoredFiles files) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.purchases = purchases;
        this.files = files;
    }

    @Override
    public List<String> tables() {
        return List.of("employee_document", "vacation_request", "payroll_payment", "payroll_schedule",
                "payroll_tenant", "license_request", "purchase_request", "shadow_spend", "subscription_access",
                "access_change", "license_lease", "renewal_reminder", "limit_request", "cabinet_tenant", "document_signature", "signing_key",
                "payroll_unmatched");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.deleted("cabinet.shadow_spend", shadow.erase(subject.tenantId(), subject.employeeId()));
        // the invoices and receipts of their purchase requests go with them
        purchases.keysOf(subject.employeeId()).forEach(files::delete);
        report.deleted("cabinet.purchase_request", purchases.deleteOf(subject.employeeId()));
        report.deleted("cabinet.license_request", jdbc.sql("delete from license_request where employee_id = ?")
                .param(subject.employeeId()).update());
        report.deleted("cabinet.license_lease", jdbc.sql("delete from license_lease where employee_id = ?")
                .param(subject.employeeId()).update());
        jdbc.sql("update license_lease set lender_id = null where lender_id = ?").param(subject.employeeId()).update();
        for (String email : subject.emails()) {
            report.deleted("cabinet.signing_key", jdbc.sql("delete from signing_key where email = ?").param(email)
                    .update());
            // payments that named the person's address and were paid to nobody (R1)
            report.deleted("cabinet.payroll_unmatched", jdbc.sql(
                    "delete from payroll_unmatched where lower(reported_email) = lower(?)").param(email).update());
        }
        // payments paid to nobody that name the person only by their full name (no address): the same cleaned name
        // the ingest pipeline keys people by (R1); compared in Java, with the cleaning the application uses
        if (subject.nameKey() != null && !subject.nameKey().isBlank()) {
            List<java.util.UUID> byName = jdbc.sql("select id, reported_name from payroll_unmatched where reported_email is null")
                    .query((rs, i) -> ai.costguard.core.shared.names.Names.clean(rs.getString("reported_name"))
                            .equals(subject.nameKey()) ? rs.getObject("id", java.util.UUID.class) : null)
                    .list().stream().filter(java.util.Objects::nonNull).toList();
            if (!byName.isEmpty()) {
                report.deleted("cabinet.payroll_unmatched", jdbc.sql("delete from payroll_unmatched where id in (:ids)")
                        .param("ids", byName).update());
            }
        }
        report.deleted("cabinet.limit_request", jdbc.sql("delete from limit_request where employee_id = ?")
                .param(subject.employeeId()).update());
        report.deleted("cabinet.access_change", jdbc.sql("delete from access_change where employee_id = ?")
                .param(subject.employeeId()).update());
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
        int signatures = jdbc.sql("""
                select count(*) from document_signature s join employee_document d on d.id = s.document_id
                where d.employee_id = ?""").param(subject.employeeId()).query(Integer.class).single();
        if (signatures > 0) {
            report.retained("cabinet.document_signature", signatures,
                    "signatures of personnel records the employer keeps by law (ТК РФ, 125-ФЗ; 63-ФЗ)", null);
        }
        if (kept > 0) {
            report.retained("cabinet.employee_document", kept,
                    "personnel records the employer keeps by law (ТК РФ, 125-ФЗ)", null);
        }
    }

    /** The files go with the company's uploads: they share its prefix in the storage (imports erases it). */
    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "cabinet", "signing_key", "document_signature", "limit_request", "renewal_reminder", "license_lease", "access_change", "subscription_access", "shadow_spend", "purchase_request", "license_request", "payroll_payment", "payroll_schedule",
                "payroll_unmatched", "vacation_request", "employee_document");
        jdbc.sql("delete from payroll_tenant where tenant_id = ?").param(tenantId).update();
        jdbc.sql("delete from cabinet_tenant where tenant_id = ?").param(tenantId).update();
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("cabinet.vacation_request", jdbc.sql("select * from vacation_request where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.payroll_payment", jdbc.sql("select * from payroll_payment where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.employee_document", jdbc.sql("select id, kind, title, number, signed_on, file_name, content_type, size, uploaded_by, created_at from employee_document where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.document_signature", jdbc.sql("select * from document_signature where lower(signer_email) in (:emails)").param("id", subject.employeeId()).param("emails", emails(subject)).query().listOfRows());
        export.rows("cabinet.purchase_request", jdbc.sql("select * from purchase_request where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.license_request", jdbc.sql("select * from license_request where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.license_lease", jdbc.sql("select * from license_lease where employee_id = :id or lender_id = :id").param("id", subject.employeeId()).param("emails", emails(subject)).query().listOfRows());
        export.rows("cabinet.limit_request", jdbc.sql("select * from limit_request where employee_id = ?").param(subject.employeeId()).query().listOfRows());
        export.rows("cabinet.signing_key", jdbc.sql("select email, method, updated_at from signing_key where lower(email) in (:emails)").param("id", subject.employeeId()).param("emails", emails(subject)).query().listOfRows());
    }

    private static java.util.Collection<String> emails(Subject subject) {
        // an empty IN list is not valid SQL; nobody has this address
        return subject.emails().isEmpty() ? java.util.List.of("-") : subject.emails();
    }
}
