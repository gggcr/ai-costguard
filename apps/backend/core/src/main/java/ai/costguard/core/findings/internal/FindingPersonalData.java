package ai.costguard.core.findings.internal;

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
 * The history of a finding keeps who moved it, by e-mail; erasing the person puts their pseudonym there. A finding
 * points at its owner by id only (ADR 0021).
 */
@Component
class FindingPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    FindingPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("finding", "finding_event", "finding_wakeup", "audit_tenant");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.anonymized("findings.finding_event.by_whom", jdbc.sql("""
                update finding_event set by_whom = :pseudonym where lower(by_whom) in (:emails)
                """).param("pseudonym", subject.pseudonym()).param("emails", subject.emails()).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "findings", "finding_event", "finding", "finding_wakeup",
                "audit_tenant");
    }
}
