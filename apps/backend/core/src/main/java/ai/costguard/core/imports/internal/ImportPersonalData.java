package ai.costguard.core.imports.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What sources reported about people (INGEST_ENTITY: employees, licenses with their last sign-in), who uploaded a
 * file, and the files (ADR 0021). Uploaded invoices and statements are the company's accounting documents: they are
 * kept for the accounting term even when they mention an erased person, then the job deletes them.
 */
@Component
class ImportPersonalData implements DataHolder {

    /** Entities that describe a person, or what a person did. */
    private static final String PEOPLE = "('EMPLOYEE', 'LICENSE_ASSIGNMENT', 'ACTIVITY_EVENT')";

    private final JdbcClient jdbc;
    private final FileStore files;

    ImportPersonalData(JdbcClient jdbc, FileStore files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    @Override
    public List<String> tables() {
        return List.of("import_job", "import_review_task", "ingest_entity");
    }

    /** A Postgres regular expression that matches the text literally. */
    private static String literal(String text) {
        return text.replaceAll("[\\\\.+*?^$()\\[\\]{}|]", "\\\\$0");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        Map<String, Object> params = new TreeMap<>();
        List<String> mentions = new ArrayList<>();
        int i = 0;
        for (String email : subject.emails()) {
            params.put("e" + i, email);
            mentions.add("position(:e" + i + " in lower(entity_key)) > 0 or position(:e" + i
                    + " in lower(payload::text)) > 0");
            i++;
        }
        params.put("employee", subject.employeeId());
        params.put("nameKey", subject.tenantId() + ":employee:name:" + subject.nameKey());
        String about = "linked_id = :employee or entity_key = :nameKey"
                + (mentions.isEmpty() ? "" : " or " + String.join(" or ", mentions));
        report.deleted("imports.ingest_entity", jdbc.sql("delete from ingest_entity where entity_type in " + PEOPLE
                + " and (" + about + ")").params(params).update());

        // Other entities (an invoice, a payment) are the company's; the e-mail in them gives way to the pseudonym.
        int anonymized = 0;
        for (String email : subject.emails()) {
            anonymized += jdbc.sql("""
                    update ingest_entity
                    set payload = regexp_replace(payload::text, :pattern, :pseudonym, 'gi')::jsonb
                    where position(:email in lower(payload::text)) > 0
                    """).param("pattern", literal(email)).param("pseudonym", subject.pseudonym())
                    .param("email", email).update();
        }
        report.anonymized("imports.ingest_entity.payload", anonymized);

        report.anonymized("imports.import_job.uploaded_by", jdbc.sql("""
                update import_job set uploaded_by = :pseudonym where lower(uploaded_by) in (:emails)
                """).param("pseudonym", subject.pseudonym()).param("emails", subject.emails()).update());

        long uploads = jdbc.sql("select count(*) from import_job").query(Long.class).single();
        if (uploads > 0) {
            report.retained("imports.files", uploads, "Загруженные счета и выписки являются учётными документами "
                    + "компании и могут упоминать человека; удаляются задачей хранения через учётный срок", null);
        }
    }

    @Override
    public void expire(Retention retention, Report report) {
        Timestamp before = Timestamp.from(retention.since(retention.importFiles()));
        record Job(UUID id, String key) {
        }
        List<Job> old = jdbc.sql("select id, storage_key from import_job where uploaded_at < ?").param(before)
                .query((rs, n) -> new Job(rs.getObject("id", UUID.class), rs.getString("storage_key"))).list();
        int deleted = 0;
        for (Job job : old) {
            deleted += files.delete(job.key()) ? 1 : 0;
        }
        report.deleted("imports.files", deleted);
        report.deleted("imports.import_review_task", jdbc.sql("""
                delete from import_review_task t using import_job j where j.id = t.job_id and j.uploaded_at < ?
                """).param(before).update());
        report.deleted("imports.import_job", jdbc.sql("delete from import_job where uploaded_at < ?").param(before)
                .update());
        report.deleted("imports.ingest_entity", jdbc.sql("delete from ingest_entity where entity_type in " + PEOPLE
                + " and last_seen < ?").param(Timestamp.from(retention.since(retention.activity()))).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        report.deleted("imports.files", files.deleteTenant(tenantId));
        TenantRows.delete(jdbc, tenantId, report, "imports", "import_review_task", "import_job", "ingest_entity");
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("imports.import_job", jdbc.sql("select id, file_name, type, status, uploaded_by, uploaded_at from import_job where lower(uploaded_by) in (:emails)").param("emails", emails(subject)).query().listOfRows());
    }

    private static java.util.Collection<String> emails(Subject subject) {
        // an empty IN list is not valid SQL; nobody has this address
        return subject.emails().isEmpty() ? java.util.List.of("-") : subject.emails();
    }
}
