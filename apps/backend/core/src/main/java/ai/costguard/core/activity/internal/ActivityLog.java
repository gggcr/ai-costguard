package ai.costguard.core.activity.internal;

import ai.costguard.core.org.UserDirectory;
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
    private final UserDirectory users;

    ActivityLog(ActivityRepository repository, JdbcClient jdbc, Clock clock, UserDirectory users) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
        this.users = users;
    }

    @EventListener
    void on(ActionTaken e) {
        UUID actor = null;
        String system = e.system();
        if (system == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
                // a person with a CostGuard account is logged as the company stored the account (by the subject):
                // the name and e-mail of the token are the person's own to change in Keycloak (audit of 2026-11-13)
                var account = users.userBySubject(jwt.getSubject());
                String name = account.map(UserDirectory.UserCard::name).filter(n -> !n.isBlank())
                        .or(() -> account.map(UserDirectory.UserCard::email))
                        .orElseGet(() -> jwt.getClaimAsString("name") != null ? jwt.getClaimAsString("name")
                                : jwt.getClaimAsString("preferred_username"));
                String email = account.map(UserDirectory.UserCard::email)
                        .orElseGet(() -> jwt.getClaimAsString("email"));
                actor = repository.actor(e.tenantId(), jwt.getSubject(), name, email);
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

    /** What this module holds about the person (ADR 0079): the same rows erasure deletes or anonymizes. */
    @Override
    public void exportEmployee(Subject subject, ai.costguard.core.privacy.PersonalDataExport export) {
        export.rows("activity.activity_entry", jdbc.sql("select e.at, e.action, e.target_type, e.target_id, e.details, a.name, a.email from activity_entry e join activity_actor a on a.id = e.actor_id where lower(a.email) in (:emails)").param("emails", emails(subject)).query().listOfRows());
    }

    private static java.util.Collection<String> emails(Subject subject) {
        // an empty IN list is not valid SQL; nobody has this address
        return subject.emails().isEmpty() ? java.util.List.of("-") : subject.emails();
    }
}
