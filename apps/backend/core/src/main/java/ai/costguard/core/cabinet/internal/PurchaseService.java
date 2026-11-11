package ai.costguard.core.cabinet.internal;

import ai.costguard.core.imports.StoredFiles;
import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.DepartmentHeads;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запрос на покупку ПО (ADR 0056). The request is checked against the company's licenses first: a program the
 * company already has is not bought again; the employee gets it from the pool or IT gives back an idle one
 * (ADR 0055). Otherwise finance decides. The employee and the head of their department hear the answer.
 */
@Service
class PurchaseService {

    private final PurchaseRepository purchases;
    private final LicensePool pool;
    private final LicenseService licenses;
    private final StoredFiles files;
    private final PersonalNotices notices;
    private final DepartmentHeads heads;
    private final EmployeeDirectory employees;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int idleDays;

    PurchaseService(PurchaseRepository purchases, LicensePool pool, LicenseService licenses, StoredFiles files,
            PersonalNotices notices, DepartmentHeads heads, EmployeeDirectory employees, Whoami whoami,
            TenantSession session, ApplicationEventPublisher events, Clock clock,
            @Value("${costguard.audit.unused-days:30}") int idleDays) {
        this.purchases = purchases;
        this.pool = pool;
        this.licenses = licenses;
        this.files = files;
        this.notices = notices;
        this.heads = heads;
        this.employees = employees;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
        this.idleDays = idleDays;
    }

    record Ask(String product, String vendor, BigDecimal amount, String currency, PurchasePeriod period,
            String reason, String fileName, byte[] file) {
    }

    @Transactional
    PurchaseRequest ask(UUID tenantId, Jwt jwt, Ask a) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        if (a.product() == null || a.product().isBlank()) {
            throw CabinetProblem.PURCHASE_PRODUCT.failure();
        }
        if (a.file() != null && a.file().length > DocumentService.MAX_SIZE) {
            throw CabinetProblem.FILE_TOO_LARGE.failure();
        }
        UUID id = UUID.randomUUID();
        String key = null;
        if (a.file() != null && a.file().length > 0) {
            key = tenantId + "/purchases/" + id;
            files.put(key, a.file());
        }
        String currency = a.currency() == null || a.currency().isBlank()
                ? employees.company().map(EmployeeDirectory.CompanyCard::currency).orElse("RUB") : a.currency();
        Optional<LicensePool.Offer> offer = pool.match(a.product(), idleDays)
                .or(() -> a.vendor() == null ? Optional.empty() : pool.match(a.vendor(), idleDays));

        String status = "pending";
        String comment = null;
        UUID licenseRequest = null;
        if (offer.isPresent()) {
            LicensePool.Offer o = offer.get();
            boolean held = pool.heldBy(me).stream().anyMatch(h -> h.subscriptionId().equals(o.subscriptionId()));
            status = "has_license";
            if (held) {
                comment = "У вас уже есть лицензия " + o.product();
            } else if (o.free() > 0 || o.idle() > 0) {
                LicenseRequest r = licenses.ask(tenantId, me, o.subscriptionId(), "Вместо покупки: " + a.product());
                licenseRequest = r.id();
                comment = "granted".equals(r.status())
                        ? "В компании есть свободная лицензия " + o.product() + ": она выдана вам из пула"
                        : "У коллеги простаивает лицензия " + o.product() + ": ИТ вернёт её в пул и выдаст вам";
            } else {
                // the company has the program, but no seat is free or idle: buying a seat is finance's call
                status = "pending";
                comment = null;
            }
        }
        purchases.insert(tenantId, new PurchaseRepository.New(id, me, a.product().strip(), blank(a.vendor()),
                a.amount().setScale(2, RoundingMode.HALF_UP), currency, a.period().name(), blank(a.reason()), key,
                key == null ? null : a.fileName(), status, offer.map(LicensePool.Offer::subscriptionId).orElse(null),
                licenseRequest, comment, clock.instant()));
        if ("has_license".equals(status)) {
            tell(tenantId, me, "Отклонено: " + comment, "warning");
        }
        events.publishEvent(ActionTaken.of(tenantId, "purchase.requested", "purchase_request", id,
                ActionTaken.details("employeeId", me, "status", status)));
        return purchases.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    List<PurchaseRequest> mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        return purchases.of(whoami.employee(jwt));
    }

    @Transactional
    void cancel(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        PurchaseRequest r = purchases.find(id).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.PURCHASE_NOT_FOUND::failure);
        if (!purchases.lockPending(r.id())) {
            throw CabinetProblem.PURCHASE_DECIDED.failure();
        }
        purchases.decide(id, "cancelled", Whoami.who(jwt), clock.instant(), null);
    }

    @Transactional(readOnly = true)
    DocumentFile myFile(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        PurchaseRequest r = purchases.find(id).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.PURCHASE_NOT_FOUND::failure);
        return file(r);
    }

    @Transactional(readOnly = true)
    DocumentFile file(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return file(purchases.find(id).orElseThrow(CabinetProblem.PURCHASE_NOT_FOUND::failure));
    }

    private DocumentFile file(PurchaseRequest r) {
        String key = purchases.fileKey(r.id()).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        byte[] bytes = files.get(key).orElseThrow(CabinetProblem.DOCUMENT_NOT_FOUND::failure);
        return new DocumentFile(r.fileName(), "application/octet-stream", bytes);
    }

    @Transactional(readOnly = true)
    List<PurchaseRequest> all(UUID tenantId) {
        session.bind(tenantId);
        return purchases.all();
    }

    @Transactional
    PurchaseRequest decide(UUID tenantId, UUID id, PurchaseDecision decision, String by) {
        session.bind(tenantId);
        if (!purchases.lockPending(id)) {
            throw CabinetProblem.PURCHASE_DECIDED.failure();
        }
        PurchaseRequest r = purchases.find(id).orElseThrow();
        purchases.decide(id, decision.approve() ? "approved" : "rejected", by, clock.instant(), decision.comment());
        String note = decision.comment() == null || decision.comment().isBlank() ? "" : ": " + decision.comment().strip();
        tell(tenantId, r.employeeId(), (decision.approve() ? "Одобрено: " : "Отклонено: ") + "покупка " + r.product()
                + note, decision.approve() ? "good" : "warning");
        events.publishEvent(ActionTaken.of(tenantId, decision.approve() ? "purchase.approved" : "purchase.rejected",
                "purchase_request", id, ActionTaken.details("employeeId", r.employeeId())));
        return purchases.find(id).orElseThrow();
    }

    /** The employee and the head of their department (the manager) hear the decision. */
    private void tell(UUID tenantId, UUID employeeId, String text, String level) {
        notices.send(tenantId, employeeId, text, "/cabinet/purchases", level);
        String name = employees.card(employeeId).map(EmployeeDirectory.EmployeeCard::name).orElse("сотрудника");
        heads.headOf(employeeId).ifPresent(head -> notices.send(tenantId, head, "Запрос " + name + ". " + text,
                "/cabinet/purchases", level));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
