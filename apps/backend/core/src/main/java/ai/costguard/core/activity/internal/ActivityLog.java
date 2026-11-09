package ai.costguard.core.activity.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import ai.costguard.core.shared.activity.ActionTaken;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Writes {@link ActionTaken} in the transaction that published it, which is already bound to the tenant: if the
 * entry cannot be written, the change rolls back with it. A plain listener on purpose, not an outbox one: an entry
 * that came later, or never, would make the log a story rather than a record.
 */
@Component
class ActivityLog implements DataHolder {

    private final ActivityRepository repository;
    private final JdbcClient jdbc;
    private final Clock clock;

    ActivityLog(ActivityRepository repository, JdbcClient jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener
    void on(ActionTaken e) {
        UUID actor = null;
        String system = e.system();
        if (system == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
                String name = jwt.getClaimAsString("name");
                actor = repository.actor(e.tenantId(), jwt.getSubject(),
                        name != null ? name : jwt.getClaimAsString("preferred_username"),
                        jwt.getClaimAsString("email"));
            } else {
                system = "core";
            }
        }
        repository.insert(e.tenantId(), clock.instant(), actor, system, e.action(), e.targetType(), e.targetId(),
                e.details());
    }

    @Override
    public List<String> tables() {
        return List.of("activity_entry", "activity_actor");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        report.anonymized("activity.activity_actor",
                repository.eraseActors(subject.emails(), subject.accounts(), clock.instant()));
    }

    @Override
    public void expire(Retention retention, Report report) {
        report.deleted("activity.activity_entry", repository.deleteBefore(retention.since(retention.actionLog())));
        report.deleted("activity.activity_actor", repository.deleteIdleActors());
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "activity", "activity_entry", "activity_actor");
    }
}
