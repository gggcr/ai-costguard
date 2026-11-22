package ai.costguard.core.findings.internal;

import ai.costguard.core.findings.FindingOpened;
import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.findings.InvoiceEvidence;
import ai.costguard.core.findings.SavingsConfirmed;
import ai.costguard.core.org.UserDirectory;
import ai.costguard.core.shared.names.Currencies;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final BigDecimal confirmShare;
    private final UserDirectory users;

    /**
     * @param confirmShare the share of the expected saving an invoice has to show to confirm it
     *     ({@code costguard.findings.confirm-share}, 0.9): prices and VAT round, a seat may be billed for part of a
     *     month
     */
    FindingService(FindingRepository findings, TenantSession session, Clock clock, ApplicationEventPublisher events,
            @Value("${costguard.findings.confirm-share:0.9}") BigDecimal confirmShare, UserDirectory users) {
        this.users = users;
        this.findings = findings;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.confirmShare = confirmShare;
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

    @Override
    @Transactional
    public void settled(UUID tenantId, String fingerprint, String what) {
        session.bind(tenantId);
        findings.lockByFingerprint(fingerprint).ifPresent(f -> {
            FindingStatus status = FindingStatus.of(f.status());
            if (status == FindingStatus.accepted || status == FindingStatus.in_progress) {
                move(tenantId, f, FindingStatus.confirmed, f.ownerId(), null, null, 0, SYSTEM,
                        what + ". Экономия подтверждена");
                events.publishEvent(new SavingsConfirmed(tenantId, f.id(), f.title(), f.expectedSavings()));
            } else if (Transitions.AUTO_CLOSABLE.contains(status)) {
                move(tenantId, f, FindingStatus.auto_closed, f.ownerId(), null, null, 0, SYSTEM,
                        what + ". Закрыта автоматически");
            }
        });
    }

    @Override
    @Transactional
    public void invoiced(UUID tenantId, InvoiceEvidence invoice) {
        session.bind(tenantId);
        for (FindingRepository.InProgress p : findings.lockInProgressOn(invoice.subscriptionId())) {
            Finding f = findings.find(p.id()).orElseThrow();
            if (Transitions.settledByEvent(f.rule()) || f.expectedSavings().signum() <= 0) {
                continue;
            }
            LocalDate task = p.taskAt() == null ? null : LocalDate.ofInstant(p.taskAt(), clock.getZone());
            if (task != null && invoice.issuedOn().isBefore(task)) {
                continue; // an invoice from before the work tells nothing about it
            }
            // the last invoice before the task is what the company paid without the change
            Optional<InvoiceEvidence.Earlier> before = invoice.earlier().stream()
                    .filter(e -> task == null ? !e.issuedOn().isAfter(invoice.issuedOn()) : e.issuedOn().isBefore(task))
                    .findFirst();
            if (before.isEmpty()) {
                findings.log(tenantId, f.id(), clock.instant(), SYSTEM, "Пришёл счёт " + invoice.label()
                        + ", но счёта до задачи нет: сравнить не с чем. Подтвердите экономию вручную");
                continue;
            }
            BigDecimal expected = f.expectedSavings().multiply(BigDecimal.valueOf(invoice.yearly() ? 12 : 1));
            BigDecimal less = before.get().total().subtract(invoice.total());
            String compared = "было " + money(before.get().total(), invoice.currency()) + ", стало "
                    + money(invoice.total(), invoice.currency()) + ", ожидалось меньше на "
                    + money(expected, invoice.currency());
            if (less.compareTo(expected.multiply(confirmShare)) >= 0) {
                move(tenantId, f, FindingStatus.confirmed, f.ownerId(), null, null, 0, SYSTEM,
                        "Экономия видна в счёте " + invoice.label() + " (" + compared + "). Подтверждена");
                events.publishEvent(new SavingsConfirmed(tenantId, f.id(), f.title(), f.expectedSavings()));
                continue;
            }
            int missed = f.missedInvoices() + 1;
            if (missed >= INVOICES_BEFORE_REVIEW) {
                move(tenantId, f, FindingStatus.review, f.ownerId(), null, null, 0, SYSTEM,
                        "Экономии нет и в счёте " + invoice.label() + " (" + compared
                                + "): два счёта подряд. Вернулась на проверку");
            } else {
                move(tenantId, f, FindingStatus.in_progress, f.ownerId(), null, null, missed, SYSTEM,
                        "Экономии нет в счёте " + invoice.label() + " (" + compared + "), " + missed + " из "
                                + INVOICES_BEFORE_REVIEW);
            }
        }
    }

    private static String money(BigDecimal amount, String currency) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString() + Currencies.suffix(currency);
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

    /**
     * Who moved the finding, as its history stores it: the address of the CostGuard account of the token's subject;
     * a token no account is linked to keeps what the controller passed (its e-mail, or its subject). The token's own
     * e-mail is the person's to change in Keycloak, and nothing else records who did it (audit of 2026-11-13).
     */
    private String by(String subject, String tokenBy) {
        return users.userBySubject(subject).map(UserDirectory.UserCard::email).orElse(tokenBy);
    }

    @Transactional
    Finding act(UUID tenantId, UUID id, ActionRequest request, String subject, String tokenBy) {
        session.bind(tenantId);
        String by = by(subject, tokenBy);
        Finding f = findings.lock(id).orElseThrow(FindingProblem.FINDING_NOT_FOUND::failure);
        FindingStatus status = FindingStatus.of(f.status());
        if (!Transitions.ALLOWED.get(status).contains(request.action())
                || Transitions.settledByEvent(f.rule()) && Transitions.BY_INVOICE.contains(request.action())) {
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
            case createTask -> {
                move(tenantId, f, FindingStatus.in_progress, f.ownerId(), null, null, 0, by,
                        "Создана задача" + (request.reason() == null ? "" : ": " + request.reason()));
                findings.taskCreated(f.id(), clock.instant());
            }
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
