package ai.costguard.core.notifications.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.TenantRows;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The feed names nobody (its texts say what happened, its links carry ids) and is kept for a term. */
@Component
class NotificationPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    NotificationPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("notification");
    }

    @Override
    public void expire(Retention retention, Report report) {
        report.deleted("notifications.notification", jdbc.sql("delete from notification where at < ?")
                .param(Timestamp.from(retention.since(retention.notifications()))).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "notifications", "notification");
    }
}
