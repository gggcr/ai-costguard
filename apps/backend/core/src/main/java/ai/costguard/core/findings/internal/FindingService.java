package ai.costguard.core.findings.internal;

import ai.costguard.core.findings.FindingOpened;
import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.findings.SavingsConfirmed;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FindingService implements FindingsIntake {

    private static final String SYSTEM = "Движок аудита";
    /** Two invoices in a row without the saving send the finding back to review: frame 8. */
    private static final int INVOICES_BEFORE_REVIEW = 2;

    private final FindingRepository findings;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    FindingService(FindingRepository findings, TenantSession session, Clock clock, ApplicationEventPublisher events) {
        this.findings = findings;
        this.session = session;
        this.clock = clock;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID detected(UUID tenantId, String fingerprint, String rule, String title, String description,
            BigDecimal savings, UUID subscriptionId, UUID agentId) {
        session.bind(tenantId);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        if (findings.insert(tenantId, id, fingerprint, rule, title, description, savings, subscriptionId, agentId, now)) {
            findings.log(tenantId, id, now, SYSTEM, "Находка создана по правилу " + rule);
            events.publishEvent(new FindingOpened(tenantId, id, title, savings));
            return id;
        }
        Finding existing = findings.lockByFingerprint(fingerprint).orElseThrow();
        findings.refresh(existing.id(), title, description, savings);
        if (FindingStatus.of(existing.status()) == FindingStatus.auto_closed) {
            move(tenantId, existing, FindingStatus.new_, existing.ownerId(), null, null, 0, SYSTEM,
                    "Условие вернулось. Находка открыта снова");
            events.publishEvent(new FindingOpened(tenantId, existing.id(), title, savings));
        }
        return existing.id();
    }

    @Override
    @Transactional
    public void resolved(UUID tenantId, String fingerprint) {
        session.bind(tenantId);
        findings.lockByFingerprint(fingerprint)
                .filter(f -> Transitions.AUTO_CLOSABLE.contains(FindingStatus.of(f.status())))
                .ifPresent(f -> move(tenantId, f, FindingStatus.auto_closed, f.ownerId(), null, null, 0, SYSTEM,
                        "Условие исчезло. Закрыта автоматически"));
    }

    private void move(UUID tenantId, Finding f, FindingStatus to, UUID owner, LocalDate until, String reason,
            int missed, String by, String text) {
        findings.update(f.id(), to.db(), owner, until, reason, missed);
        findings.log(tenantId, f.id(), clock.instant(), by, text);
        if (to == FindingStatus.postponed) {
            findings.scheduleWakeup(f.id(), tenantId, until);
        } else {
            findings.removeWakeup(f.id());
        }
    }

    @Transactional(readOnly = true)
    List<Finding> list(UUID tenantId, String status) {
        session.bind(tenantId);
        return findings.list(status);
    }

    @Transactional(readOnly = true)
    Finding get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return findings.find(id).orElseThrow(FindingProblem.FINDING_NOT_FOUND::failure);
    }

    @Transactional(readOnly = true)
    FindingTotals totals(UUID tenantId) {
        session.bind(tenantId);
        return findings.totals();
    }

    @Transactional
    Finding act(UUID tenantId, UUID id, ActionRequest request, String by) {
        session.bind(tenantId);
        Finding f = findings.lock(id).orElseThrow(FindingProblem.FINDING_NOT_FOUND::failure);
        FindingStatus status = FindingStatus.of(f.status());
        if (!Transitions.ALLOWED.get(status).contains(request.action())) {
            throw FindingProblem.ACTION_NOT_ALLOWED.failure();
        }
        switch (request.action()) {
            case assign -> {
                require(request.ownerId() != null);
                move(tenantId, f, FindingStatus.review, request.ownerId(), null, null, 0, by, "Назначен владелец");
            }
            case accept -> move(tenantId, f, FindingStatus.accepted, f.ownerId(), null, null, 0, by, "Принята");
            case postpone -> {
                require(request.date() != null && request.date().isAfter(LocalDate.now(clock)));
                move(tenantId, f, FindingStatus.postponed, f.ownerId(), request.date(), null, 0, by,
                        "Отложена до " + request.date());
            }
            case reject -> {
                require(request.reason() != null && !request.reason().isBlank());
                move(tenantId, f, FindingStatus.rejected, f.ownerId(), null, request.reason(), 0, by,
                        "Отклонена: " + request.reason());
            }
            case createTask -> move(tenantId, f, FindingStatus.in_progress, f.ownerId(), null, null, 0, by,
                    "Создана задача" + (request.reason() == null ? "" : ": " + request.reason()));
            case confirm -> {
                move(tenantId, f, FindingStatus.confirmed, f.ownerId(), null, null, 0, by,
                        "Экономия видна в следующем счёте. Подтверждена");
                events.publishEvent(new SavingsConfirmed(tenantId, f.id(), f.title(), f.expectedSavings()));
            }
            case missInvoice -> {
                int missed = f.missedInvoices() + 1;
                if (missed >= INVOICES_BEFORE_REVIEW) {
                    move(tenantId, f, FindingStatus.review, f.ownerId(), null, null, 0, by,
                            "Экономии нет два счёта подряд. Вернулась на проверку");
                } else {
                    move(tenantId, f, FindingStatus.in_progress, f.ownerId(), null, null, missed, by,
                            "Экономии нет в очередном счёте (" + missed + " из " + INVOICES_BEFORE_REVIEW + ")");
                }
            }
        }
        return findings.find(id).orElseThrow();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw FindingProblem.DETAIL_REQUIRED.failure();
        }
    }

    @Transactional(readOnly = true)
    List<DueFinding> dueWakeups() {
        return findings.dueWakeups(LocalDate.now(clock));
    }

    @Transactional
    void wake(DueFinding due) {
        session.bind(due.tenantId());
        Optional<Finding> f = findings.lock(due.findingId());
        if (f.filter(x -> FindingStatus.of(x.status()) == FindingStatus.postponed).isPresent()) {
            move(due.tenantId(), f.get(), FindingStatus.new_, f.get().ownerId(), null, null, 0, SYSTEM,
                    "Наступила дата. Находка снова новая");
        } else {
            findings.removeWakeup(due.findingId());
        }
    }
}
