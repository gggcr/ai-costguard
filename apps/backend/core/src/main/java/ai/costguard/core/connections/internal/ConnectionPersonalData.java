package ai.costguard.core.connections.internal;

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
 * Settings of a connection can name a person: the Google Workspace administrator, the user of a mailbox. The core
 * does not change them on its own, the agent would stop pulling; it says which ones to change (ADR 0021). Errors of
 * runs are masked by the agent before they get here (ADR 0023).
 */
@Component
class ConnectionPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    ConnectionPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("connection", "connection_run");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        record Setting(String type, String key, String value) {
        }
        // A value can be a list ("ignore" holds e-mails separated by commas), so look inside it.
        List<Setting> named = jdbc.sql("""
                select c.type, s.key, lower(s.value) as value from connection c, jsonb_each_text(c.settings) s
                order by c.type, s.key
                """).query((rs, n) -> new Setting(rs.getString("type"), rs.getString("key"), rs.getString("value")))
                .list().stream().filter(s -> subject.emails().stream().anyMatch(s.value()::contains)).toList();
        for (Setting s : named) {
            report.manual("connections.connection.settings", "Настройка " + s.key() + " подключения " + s.type()
                    + " указывает на удалённого сотрудника; замените её на странице подключений");
        }
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        for (String type : jdbc.sql("select type from connection").query(String.class).list()) {
            report.tombstone("connectors.config.v1", tenantId + ":" + type);
        }
        TenantRows.delete(jdbc, tenantId, report, "connections", "connection_run", "connection");
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        // settings of connections may name the person's address; the export says which (not other settings)
        java.util.List<java.util.Map<String, Object>> named = new java.util.ArrayList<>();
        jdbc.sql("select c.type, s.key, lower(s.value) as value from connection c, jsonb_each_text(c.settings) s")
                .query().listOfRows().stream()
                .filter(r -> subject.emails().stream().anyMatch(String.valueOf(r.get("value"))::contains))
                .forEach(r -> named.add(java.util.Map.of("connection", r.get("type"), "setting", r.get("key"))));
        export.rows("connections.connection.settings", named);
    }
}
