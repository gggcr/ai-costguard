package ai.costguard.core.cabinet.internal;

import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.shared.names.Language;
import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пул лицензий (ADR 0055): a person asks for a program; a free seat is theirs at once; otherwise IT decides, and an
 * idle license of a colleague goes back to the pool and to them. Asked for N days (ADR 0066), the license goes
 * back when they are over, to that colleague when it was theirs.
 */
@Service
class LicenseService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final LicensePool pool;
    private final LicenseRepository requests;
    private final LeaseRepository leases;
    private final LeaseService leasing;
    private final PersonalNotices notices;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int idleDays;

    LicenseService(LicensePool pool, LicenseRepository requests, LeaseRepository leases, LeaseService leasing,
            PersonalNotices notices, Whoami whoami,
            TenantSession session, ApplicationEventPublisher events, Clock clock,
            @Value("${costguard.audit.unused-days:30}") int idleDays) {
        this.pool = pool;
        this.requests = requests;
        this.leases = leases;
        this.leasing = leasing;
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
        return new MyLicenses(pool.heldBy(me), pool.offers(idleDays), requests.of(me), idleDays,
                leases.borrowedBy(me).stream().map(LeaseView::of).toList(),
                leases.lentBy(me).stream().map(LeaseView::of).toList());
    }

    @Transactional
    LicenseRequest ask(UUID tenantId, Jwt jwt, NewLicenseRequest request) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        return ask(tenantId, me, request.subscriptionId(), request.reason(), request.days());
    }

    /** Also for a purchase request that turns out to have a license in the pool (ADR 0056). */
    LicenseRequest ask(UUID tenantId, UUID me, UUID subscriptionId, String reason, Integer days) {
        LicensePool.Offer offer = pool.offer(subscriptionId, idleDays)
                .orElseThrow(CabinetProblem.SUBSCRIPTION_NOT_FOUND::failure);
        if (pool.heldBy(me).stream().anyMatch(h -> h.subscriptionId().equals(subscriptionId))) {
            throw CabinetProblem.LICENSE_HELD.failure();
        }
        if (requests.pendingFor(me, subscriptionId)) {
            throw CabinetProblem.LICENSE_ASKED.failure();
        }
        UUID id = UUID.randomUUID();
        Optional<UUID> given = pool.grant(subscriptionId, me);
        if (given.isPresent()) {
            events.publishEvent(new SeatChanged(tenantId, subscriptionId, me, true));
            requests.insert(tenantId, id, me, subscriptionId, reason, "granted", null, clock.instant(), days);
            if (days != null) {
                leasing.lease(tenantId, given.get(), subscriptionId, me, null, days, offer.product());
            }
            Language lang = leasing.lang();
            notices.send(tenantId, me, lang.pick("Вам выдана лицензия " + offer.product() + " из пула компании"
                    + (days == null ? "" : " на " + days + " дн."),
                    "You were given a " + offer.product() + " license from the company pool"
                    + (days == null ? "" : " for " + days + (days == 1 ? " day" : " days"))),
                    "/cabinet/licenses", "good");
        } else {
            UUID idle = pool.idlest(subscriptionId, idleDays, me).map(LicensePool.Idle::licenseId).orElse(null);
            requests.insert(tenantId, id, me, subscriptionId, reason, "pending", idle, clock.instant(), days);
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
            UUID lender = null;
            Optional<UUID> given = pool.grant(r.subscriptionId(), r.employeeId());
            if (given.isEmpty()) {
                LicensePool.Idle taken = idle == null ? null
                        : pool.idle(idleDays).stream().filter(i -> i.licenseId().equals(idle)).findFirst()
                                .orElse(null);
                if (taken == null) {
                    taken = pool.idlest(r.subscriptionId(), idleDays, r.employeeId()).orElse(null);
                }
                if (taken == null) {
                    throw CabinetProblem.NO_FREE_SEAT.failure();
                }
                giveBack(tenantId, taken, r.days() != null);
                given = Optional.of(pool.grant(r.subscriptionId(), r.employeeId())
                        .orElseThrow(CabinetProblem.NO_FREE_SEAT::failure));
                lender = taken.employeeId();
            }
            if (r.days() != null) {
                leasing.lease(tenantId, given.get(), r.subscriptionId(), r.employeeId(), lender, r.days(),
                        r.product());
            }
            events.publishEvent(new SeatChanged(tenantId, r.subscriptionId(), r.employeeId(), true));
            requests.decide(id, "approved", by, clock.instant(), decision.comment());
            Language lang = leasing.lang();
            notices.send(tenantId, r.employeeId(), lang.pick("Вам выдана лицензия " + r.product()
                    + (r.days() == null ? "" : " на " + r.days() + " дн."),
                    "You were given a " + r.product() + " license"
                    + (r.days() == null ? "" : " for " + r.days() + (r.days() == 1 ? " day" : " days"))),
                    "/cabinet/licenses", "good");
        } else {
            requests.decide(id, "rejected", by, clock.instant(), decision.comment());
            String why = decision.comment() == null || decision.comment().isBlank() ? ""
                    : ": " + decision.comment().strip();
            notices.send(tenantId, r.employeeId(), leasing.lang().pick("Лицензия " + r.product() + " не выдана" + why,
                    "Your " + r.product() + " license request was declined" + why), "/cabinet/licenses", "warning");
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
            giveBack(tenantId, i, false);
            n++;
        }
        return new Reclaimed(n);
    }

    /** {@code lent}: the license goes to a colleague for a while and comes back; the lease tells the holder. */
    private void giveBack(UUID tenantId, LicensePool.Idle i, boolean lent) {
        requests.forgetIdle(i.licenseId());
        if (pool.reclaim(i.licenseId())) {
            events.publishEvent(new SeatChanged(tenantId, i.subscriptionId(), i.employeeId(), false));
            if (!lent) {
                Language lang = leasing.lang();
                notices.send(tenantId, i.employeeId(), lang.pick("Лицензия " + i.product() + " возвращена в общий пул: "
                        + (i.lastActive() == null ? "не использовалась"
                                : "не использовалась с " + DAY.format(i.lastActive()))
                        + ". Понадобится — запросите в кабинете",
                        "Your " + i.product() + " license went back to the company pool: "
                        + (i.lastActive() == null ? "it was never used"
                                : "unused since " + LeaseService.day(i.lastActive(), lang))
                        + ". Request it in your cabinet when you need it again"), "/cabinet/licenses", "info");
            }
            events.publishEvent(ActionTaken.of(tenantId, "license.reclaimed", "license_assignment", i.licenseId(),
                    ActionTaken.details("employeeId", i.employeeId(), "subscriptionId", i.subscriptionId())));
        }
    }
}
