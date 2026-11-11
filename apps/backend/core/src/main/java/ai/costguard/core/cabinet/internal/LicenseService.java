package ai.costguard.core.cabinet.internal;

import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пул лицензий (ADR 0055): a person asks for a program; a free seat is theirs at once; otherwise IT decides, and an
 * idle license of a colleague goes back to the pool and to them. A license is reassigned, never lent.
 */
@Service
class LicenseService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final LicensePool pool;
    private final LicenseRepository requests;
    private final PersonalNotices notices;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int idleDays;

    LicenseService(LicensePool pool, LicenseRepository requests, PersonalNotices notices, Whoami whoami,
            TenantSession session, ApplicationEventPublisher events, Clock clock,
            @Value("${costguard.audit.unused-days:30}") int idleDays) {
        this.pool = pool;
        this.requests = requests;
        this.notices = notices;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
        this.idleDays = idleDays;
    }

    @Transactional(readOnly = true)
    MyLicenses mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        return new MyLicenses(pool.heldBy(me), pool.offers(idleDays), requests.of(me), idleDays);
    }

    @Transactional
    LicenseRequest ask(UUID tenantId, Jwt jwt, NewLicenseRequest request) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        return ask(tenantId, me, request.subscriptionId(), request.reason());
    }

    /** Also for a purchase request that turns out to have a license in the pool (ADR 0056). */
    LicenseRequest ask(UUID tenantId, UUID me, UUID subscriptionId, String reason) {
        LicensePool.Offer offer = pool.offer(subscriptionId, idleDays)
                .orElseThrow(CabinetProblem.SUBSCRIPTION_NOT_FOUND::failure);
        if (pool.heldBy(me).stream().anyMatch(h -> h.subscriptionId().equals(subscriptionId))) {
            throw CabinetProblem.LICENSE_HELD.failure();
        }
        if (requests.pendingFor(me, subscriptionId)) {
            throw CabinetProblem.LICENSE_ASKED.failure();
        }
        UUID id = UUID.randomUUID();
        if (pool.grant(subscriptionId, me).isPresent()) {
            requests.insert(tenantId, id, me, subscriptionId, reason, "granted", null, clock.instant());
            notices.send(tenantId, me, "Вам выдана лицензия " + offer.product() + " из пула компании",
                    "/cabinet/licenses", "good");
        } else {
            UUID idle = pool.idlest(subscriptionId, idleDays, me).map(LicensePool.Idle::licenseId).orElse(null);
            requests.insert(tenantId, id, me, subscriptionId, reason, "pending", idle, clock.instant());
        }
        events.publishEvent(ActionTaken.of(tenantId, "license.requested", "license_request", id,
                ActionTaken.details("employeeId", me, "subscriptionId", subscriptionId)));
        return requests.find(id).orElseThrow();
    }

    @Transactional
    void cancel(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        LicenseRequest r = requests.find(id).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.LICENSE_REQUEST_NOT_FOUND::failure);
        if (!r.status().equals("pending")) {
            throw CabinetProblem.LICENSE_REQUEST_DECIDED.failure();
        }
        requests.decide(id, "cancelled", Whoami.who(jwt), clock.instant(), null);
    }

    @Transactional(readOnly = true)
    List<LicenseRequest> all(UUID tenantId) {
        session.bind(tenantId);
        return requests.all();
    }

    @Transactional(readOnly = true)
    List<LicensePool.Offer> overview(UUID tenantId) {
        session.bind(tenantId);
        return pool.offers(idleDays);
    }

    /** IT approves: an idle license goes back to the pool first, then a free seat goes to the person. */
    @Transactional
    LicenseRequest decide(UUID tenantId, UUID id, LicenseDecision decision, String by) {
        session.bind(tenantId);
        UUID idle = requests.lockIdle(id);
        LicenseRequest r = requests.find(id).orElseThrow();
        if (!r.status().equals("pending")) {
            throw CabinetProblem.LICENSE_REQUEST_DECIDED.failure();
        }
        if (decision.approve()) {
            if (pool.grant(r.subscriptionId(), r.employeeId()).isEmpty()) {
                LicensePool.Idle taken = idle == null ? null
                        : pool.idle(idleDays).stream().filter(i -> i.licenseId().equals(idle)).findFirst()
                                .orElse(null);
                if (taken == null) {
                    taken = pool.idlest(r.subscriptionId(), idleDays, r.employeeId()).orElse(null);
                }
                if (taken == null) {
                    throw CabinetProblem.NO_FREE_SEAT.failure();
                }
                giveBack(tenantId, taken);
                pool.grant(r.subscriptionId(), r.employeeId()).orElseThrow(CabinetProblem.NO_FREE_SEAT::failure);
            }
            requests.decide(id, "approved", by, clock.instant(), decision.comment());
            notices.send(tenantId, r.employeeId(), "Вам выдана лицензия " + r.product(), "/cabinet/licenses", "good");
        } else {
            requests.decide(id, "rejected", by, clock.instant(), decision.comment());
            notices.send(tenantId, r.employeeId(), "Лицензия " + r.product() + " не выдана"
                    + (decision.comment() == null || decision.comment().isBlank() ? "" : ": " + decision.comment().strip()),
                    "/cabinet/licenses", "warning");
        }
        events.publishEvent(ActionTaken.of(tenantId, decision.approve() ? "license.request.approved"
                : "license.request.rejected", "license_request", id, ActionTaken.details("employeeId", r.employeeId())));
        return requests.find(id).orElseThrow();
    }

    /** Every license idle for {@code idleDays} goes back to the pool; the holders are told. */
    @Transactional
    Reclaimed reclaimIdle(UUID tenantId) {
        session.bind(tenantId);
        int n = 0;
        for (LicensePool.Idle i : pool.idle(idleDays)) {
            giveBack(tenantId, i);
            n++;
        }
        return new Reclaimed(n);
    }

    private void giveBack(UUID tenantId, LicensePool.Idle i) {
        requests.forgetIdle(i.licenseId());
        if (pool.reclaim(i.licenseId())) {
            notices.send(tenantId, i.employeeId(), "Лицензия " + i.product() + " возвращена в общий пул: "
                    + (i.lastActive() == null ? "не использовалась" : "не использовалась с " + DAY.format(i.lastActive()))
                    + ". Понадобится — запросите в кабинете", "/cabinet/licenses", "info");
            events.publishEvent(ActionTaken.of(tenantId, "license.reclaimed", "license_assignment", i.licenseId(),
                    ActionTaken.details("employeeId", i.employeeId(), "subscriptionId", i.subscriptionId())));
        }
    }
}
