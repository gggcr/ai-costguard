package ai.costguard.core.notifications.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The feed names nobody (its texts say what happened, its links carry ids) and is kept for a term. Channel settings
 * (a Telegram chat id) and deliveries belong to a user and go with the user_account row (on delete cascade).
 */
@Component
class NotificationPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    NotificationPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("notification", "notification_delivery", "notification_setting", "notification_tenant",
                "telegram_link");
    }

    /** One employee's own notifications (ADR 0052) name them by being theirs: they go with the person. */
    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.deleted("notifications.personal", jdbc.sql("delete from notification where employee_id = ?")
                .param(subject.employeeId()).update());
    }

    @Override
    public void expire(Retention retention, Report report) {
        report.deleted("notifications.notification", jdbc.sql("delete from notification where at < ?")
                .param(Timestamp.from(retention.since(retention.notifications()))).update());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "notifications", "notification_delivery", "notification",
                "notification_setting", "telegram_link", "notification_tenant");
    }

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("notifications.notification", jdbc.sql("select * from notification where employee_id = ?").param(subject.employeeId()).query().listOfRows());
    }
}
