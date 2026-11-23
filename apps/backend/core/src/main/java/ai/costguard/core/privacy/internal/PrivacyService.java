package ai.costguard.core.privacy.internal;

import ai.costguard.core.privacy.CompactedRecordsErased;
import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.ErasureSubjects;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.errors.ApiException;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erasure and retention (ADR 0021). Each module erases its own rows through {@link DataHolder}; this service
 * decides when, talks to the systems outside the database (Keycloak, ClickHouse) and keeps the record.
 *
 * <p>Outside systems go first. Each of their deletions is safe to repeat, so if the database part then fails,
 * the next attempt meets nothing left to delete there and finishes the rest. The other way round, a database
 * erasure that committed before Keycloak failed would have forgotten the e-mails needed to find the account.
 */
@Service
class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private static final String RETENTION = "retention";

    private final PrivacyRepository repository;
    private final List<DataHolder> holders;
    private final ErasureSubjects subjects;
    private final KeycloakAccounts keycloak;
    private final AnalyticsRows analytics;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final PrivacyProperties properties;
    private final TransactionTemplate tx;

    PrivacyService(PrivacyRepository repository, List<DataHolder> holders, ErasureSubjects subjects,
            KeycloakAccounts keycloak, AnalyticsRows analytics, TenantSession session, Clock clock,
            ApplicationEventPublisher events, PrivacyProperties properties, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.holders = holders;
        this.subjects = subjects;
        this.keycloak = keycloak;
        this.analytics = analytics;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    private LocalDate today() {
        return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
    }

    // --- an employee ---------------------------------------------------------------------------------------------

    record Erased(Erasure erasure, boolean created) {
    }

    /** Asking again returns the record of the first erasure. */
    @Transactional
    Erased eraseEmployee(UUID tenantId, UUID employeeId) {
        return erase(tenantId, employeeId, ErasureReason.request);
    }

    private Erased erase(UUID tenantId, UUID employeeId, ErasureReason reason) {
        session.bind(tenantId);
        var done = repository.erasureOf(employeeId);
        if (done.isPresent()) {
            return new Erased(done.get(), false);
        }
        ErasureSubjects.Person person = subjects.person(employeeId)
                .orElseThrow(PrivacyProblem.EMPLOYEE_NOT_FOUND::failure);
        if (person.dismissedAt() == null) {
            throw PrivacyProblem.EMPLOYEE_ACTIVE.failure();
        }
        if (person.lastOwner()) {
            throw PrivacyProblem.LAST_OWNER.failure();
        }
        UUID id = UUID.randomUUID();
        Steps steps = new Steps();
        List<String> accounts = List.of();
        if (keycloak.configured()) {
            accounts = keycloak.find(tenantId, person.emails());
            keycloak.delete(accounts);
            steps.deleted("keycloak.user", accounts.size());
        } else {
            steps.manual("keycloak.user", "The Keycloak admin client is not configured: delete the person's account "
                    + "by hand (docs/runbooks/privacy.md)");
        }
        Subject subject = new Subject(tenantId, employeeId, person.emails(), Set.copyOf(accounts), person.nameKey(),
                "erased:" + id);
        Retention retention = retention();
        for (DataHolder holder : holders) {
            holder.eraseEmployee(subject, retention, steps);
        }
        steps.retained("kafka.ingest.normalized.v1", 0, "Messages about the person expire with the topic",
                today().plus(properties.kafkaRetention()));
        steps.retained("logs", 0, "Service logs are masked and expire", today().plus(properties.logRetention()));
        repository.insertErasure(tenantId, id, employeeId, reason, clock.instant(), steps.steps());
        Map<String, Object> details = Map.of("erasureId", id, "reason", reason.name());
        events.publishEvent(reason == ErasureReason.retention
                ? ActionTaken.bySystem(RETENTION, tenantId, "privacy.employee_erased", "employee", employeeId, details)
                : ActionTaken.of(tenantId, "privacy.employee_erased", "employee", employeeId, details));
        return new Erased(repository.erasure(id).orElseThrow(), true);
    }

    /**
     * Everything the modules hold about one person, for a request of access or portability that the company (the
     * controller) received (ADR 0079). The export itself is recorded in the activity log, without its content.
     */
    @Transactional
    java.util.Map<String, Object> exportEmployee(UUID tenantId, UUID employeeId) {
        session.bind(tenantId);
        ErasureSubjects.Person person = subjects.person(employeeId)
                .orElseThrow(PrivacyProblem.EMPLOYEE_NOT_FOUND::failure);
        Subject subject = new Subject(tenantId, employeeId, person.emails(), Set.of(), person.nameKey(), "export");
        ai.costguard.core.privacy.PersonalDataExport export = new ai.costguard.core.privacy.PersonalDataExport();
        for (DataHolder holder : holders) {
            holder.exportEmployee(subject, export);
        }
        events.publishEvent(ActionTaken.of(tenantId, "privacy.employee_exported", "employee", employeeId,
                Map.of("tables", export.tables().size())));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("employeeId", employeeId);
        out.put("exportedAt", clock.instant().toString());
        out.put("erased", person.erased());
        out.put("tables", export.tables());
        return out;
    }

    @Transactional(readOnly = true)
    List<Erasure> erasures(UUID tenantId) {
        session.bind(tenantId);
        return repository.erasures(200);
    }

    @Transactional(readOnly = true)
    Erasure erasure(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return repository.erasure(id).orElseThrow(PrivacyProblem.ERASURE_NOT_FOUND::failure);
    }

    // --- the company ---------------------------------------------------------------------------------------------

    /** Erasing runs after the grace period; asking again while it waits changes nothing. */
    @Transactional
    CompanyErasure requestCompanyErasure(UUID tenantId, String confirmName) {
        session.bind(tenantId);
        String name = subjects.companyName().orElseThrow(PrivacyProblem.COMPANY_NOT_FOUND::failure);
        if (!name.strip().equals(confirmName.strip())) {
            throw PrivacyProblem.CONFIRM_NAME_MISMATCH.failure();
        }
        var existing = repository.company(tenantId);
        if (existing.isPresent() && existing.get().status() == CompanyErasureStatus.scheduled) {
            return existing.get();
        }
        Instant now = clock.instant();
        Instant due = now.atOffset(ZoneOffset.UTC).plus(properties.companyGrace()).toInstant();
        repository.scheduleCompany(tenantId, now, due);
        events.publishEvent(ActionTaken.of(tenantId, "privacy.company_erasure_requested", "company", tenantId,
                Map.of("scheduledFor", due.toString())));
        return repository.company(tenantId).orElseThrow();
    }

    @Transactional
    void cancelCompanyErasure(UUID tenantId) {
        session.bind(tenantId);
        if (!repository.cancelCompany(tenantId, clock.instant())) {
            throw PrivacyProblem.COMPANY_ERASURE_NOT_FOUND.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "privacy.company_erasure_cancelled", "company", tenantId,
                Map.of()));
    }

    @Transactional(readOnly = true)
    CompanyErasure companyErasure(UUID tenantId) {
        return repository.company(tenantId).orElseThrow(PrivacyProblem.COMPANY_ERASURE_NOT_FOUND::failure);
    }

    /** Called by the job, outside a transaction. A company that fails is tried again on the next round. */
    int eraseDueCompanies() {
        int erased = 0;
        for (UUID tenant : repository.dueCompanies(clock.instant())) {
            try {
                erased += eraseCompany(tenant) ? 1 : 0;
            } catch (RuntimeException e) {
                log.error("Erasing company {} failed; the next round tries again", tenant, e);
                tx.executeWithoutResult(s -> repository.failCompany(tenant, e.getClass().getSimpleName()));
            }
        }
        return erased;
    }

    boolean eraseCompany(UUID tenantId) {
        Steps steps = new Steps();
        if (keycloak.configured()) {
            List<String> accounts = keycloak.ofTenant(tenantId);
            keycloak.delete(accounts);
            steps.deleted("keycloak.user", accounts.size());
        } else {
            steps.manual("keycloak.user", "The Keycloak admin client is not configured: delete the company's "
                    + "accounts by hand (docs/runbooks/privacy.md)");
        }
        if (analytics.configured()) {
            analytics.eraseTenant(tenantId);
            analytics.tables().forEach(t -> steps.erasedAll("clickhouse." + t, "Every row of the company"));
        } else {
            steps.manual("clickhouse", "ClickHouse is not configured for the core: delete the company's rows by hand "
                    + "(docs/runbooks/privacy.md)");
        }
        Boolean done = tx.execute(status -> {
            Instant now = clock.instant();
            if (!repository.lockDue(tenantId, now)) {
                return false;
            }
            session.bind(tenantId);
            repository.purgeMode();
            for (DataHolder holder : holders) {
                holder.eraseCompany(tenantId, steps);
            }
            steps.retained("near", 0, "Merkle roots and hashes on the chain carry no company and no person "
                    + "(ADR 0006, ADR 0009)", null);
            steps.retained("kafka", 0, "Messages expire with their topics; compacted records get tombstones",
                    today().plus(properties.kafkaRetention()));
            events.publishEvent(new CompactedRecordsErased(tenantId, steps.tombstones()));
            repository.completeCompany(tenantId, now, steps.steps());
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    // --- retention -----------------------------------------------------------------------------------------------

    Retention retention() {
        PrivacyProperties.RetentionTerms t = properties.retention();
        return new Retention(clock.instant(), t.dismissedEmployees(), t.activity(), t.notifications(),
                t.handoverActs(), t.importFiles(), t.actionLog());
    }

    /** Called by the job, outside a transaction: each tenant, and each employee, in a transaction of its own. */
    long applyRetention() {
        Retention retention = retention();
        long removed = 0;
        for (UUID tenant : repository.tenants()) {
            try {
                Long n = tx.execute(status -> expire(tenant, retention));
                removed += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("Retention of tenant {} failed", tenant, e);
                continue;
            }
            List<UUID> due = tx.execute(status -> {
                session.bind(tenant);
                return subjects.dismissedBefore(retention.sinceDay(retention.dismissedEmployees()));
            });
            for (UUID employee : due == null ? List.<UUID>of() : due) {
                try {
                    tx.executeWithoutResult(status -> erase(tenant, employee, ErasureReason.retention));
                    removed++;
                } catch (ApiException e) {
                    log.warn("Employee {} of tenant {} is past retention but not erased: {}", employee, tenant,
                            e.problem().code());
                } catch (RuntimeException e) {
                    log.error("Erasing employee {} of tenant {} failed", employee, tenant, e);
                }
            }
        }
        return removed;
    }

    private long expire(UUID tenant, Retention retention) {
        session.bind(tenant);
        repository.purgeMode();
        Steps steps = new Steps();
        for (DataHolder holder : holders) {
            holder.expire(retention, steps);
        }
        long deleted = steps.total(StepAction.deleted);
        long anonymized = steps.total(StepAction.anonymized);
        if (deleted + anonymized > 0) {
            events.publishEvent(ActionTaken.bySystem(RETENTION, tenant, "privacy.retention_applied", "company",
                    tenant, Map.of("deleted", deleted, "anonymized", anonymized)));
        }
        return deleted + anonymized;
    }
}
