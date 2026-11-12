package ai.costguard.core.cabinet.internal;

import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Аренда лицензии на срок и напоминание перед автопродлением (ADR 0066). A license asked for N days goes back when
 * they are over: to the colleague it was taken from when they were idle, otherwise to the pool. A license nobody uses
 * is pointed out to its holder a week before the subscription renews, so they can give it back before it is paid
 * again.
 */
@Service
class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final LeaseRepository leases;
    private final LicensePool pool;
    private final PersonalNotices notices;
    private final EmployeeDirectory employees;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int idleDays;
    private final int remindDays;

    LeaseService(LeaseRepository leases, LicensePool pool, PersonalNotices notices, EmployeeDirectory employees,
            Whoami whoami, TenantSession session, ApplicationEventPublisher events, TransactionTemplate tx,
            Clock clock, @Value("${costguard.audit.unused-days:30}") int idleDays,
            @Value("${costguard.cabinet.renewal-remind-days:7}") int remindDays) {
        this.leases = leases;
        this.pool = pool;
        this.notices = notices;
        this.employees = employees;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.idleDays = idleDays;
        this.remindDays = remindDays;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(MOSCOW));
    }

    /** A license given for {@code days}; {@code lender} is the colleague it was taken from, if any. Joins the caller. */
    void lease(UUID tenantId, UUID licenseId, UUID subscriptionId, UUID employeeId, UUID lender, int days,
            String product) {
        LocalDate endsOn = today().plusDays(days);
        leases.insert(tenantId, licenseId, subscriptionId, employeeId, lender, endsOn, clock.instant());
        if (lender != null) {
            notices.send(tenantId, lender, "Ваша лицензия " + product + " одолжена коллеге до " + DAY.format(endsOn)
                    + ": вы ей не пользовались. Она вернётся к вам сама", "/cabinet/licenses", "info");
        }
    }

    /** Gives my license back before its time (or a license of my own I do not need): it goes as when it ends. */
    @Transactional
    void release(UUID tenantId, Jwt jwt, UUID licenseId) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        LicensePool.Held held = pool.heldBy(me).stream().filter(h -> h.licenseId().equals(licenseId)).findFirst()
                .orElseThrow(CabinetProblem.LICENSE_NOT_FOUND::failure);
        var lease = leases.lock(licenseId);
        if (lease.isPresent()) {
            end(tenantId, lease.get(), true);
        } else {
            take(tenantId, held.subscriptionId(), me, licenseId);
            notices.send(tenantId, me, "Лицензия " + held.product() + " возвращена в общий пул. Спасибо: место "
                    + "достанется коллеге или не будет оплачено", "/cabinet/licenses", "good");
        }
        events.publishEvent(ActionTaken.of(tenantId, "license.released", "license_assignment", licenseId,
                ActionTaken.details("employeeId", me, "subscriptionId", held.subscriptionId())));
    }

    @Scheduled(cron = "${costguard.cabinet.morning-cron:0 0 8 * * *}", zone = "Europe/Moscow")
    void morning() {
        LocalDate today = today();
        for (UUID tenant : tx.execute(s -> leases.tenants())) {
            try {
                tx.executeWithoutResult(s -> run(tenant, today));
            } catch (RuntimeException e) {
                log.error("Leases and renewal reminders of tenant {} failed", tenant, e);
            }
        }
    }

    /** Ends the leases that are over, warns of those ending tomorrow, reminds of idle licenses renewing soon. */
    MorningResult run(UUID tenantId, LocalDate today) {
        session.bind(tenantId);
        int ended = 0;
        for (Lease l : leases.endedBy(today)) {
            end(tenantId, l, false);
            ended++;
        }
        for (Lease l : leases.remindOn(today.plusDays(1))) {
            notices.send(tenantId, l.employeeId(), "Завтра заканчивается срок лицензии " + l.product()
                    + ". Нужна дольше — запросите её снова в кабинете", "/cabinet/licenses", "warning");
        }
        int reminded = 0;
        String currency = employees.company().map(EmployeeDirectory.CompanyCard::currency).orElse("RUB");
        for (LicensePool.Renewing r : pool.renewing(today, today.plusDays(remindDays), idleDays)) {
            if (leases.remindRenewal(tenantId, r.licenseId(), r.renewalDate(), clock.instant())) {
                notices.send(tenantId, r.employeeId(), DAY.format(r.renewalDate()) + " продлится подписка "
                        + r.product() + ". Вы не пользуетесь ей"
                        + (r.lastActive() == null ? "" : " с " + DAY.format(r.lastActive()))
                        + ". Не нужна — верните лицензию в пул, и компания не заплатит за место "
                        + money(r.monthlyPrice(), currency) + " в месяц", "/cabinet/licenses", "warning");
                reminded++;
            }
        }
        return new MorningResult(ended, reminded);
    }

    private void end(UUID tenantId, Lease l, boolean early) {
        leases.delete(l.licenseId());
        take(tenantId, l.subscriptionId(), l.employeeId(), l.licenseId());
        notices.send(tenantId, l.employeeId(), (early ? "Вы вернули лицензию " : "Срок лицензии ") + l.product()
                + (early ? "" : " закончился") + (l.lenderId() == null ? ": она вернулась в общий пул"
                : ": она вернулась к коллеге"), "/cabinet/licenses", "info");
        if (l.lenderId() == null) {
            return;
        }
        boolean active = employees.card(l.lenderId()).filter(c -> c.dismissedAt() == null).isPresent();
        if (active && pool.grant(l.subscriptionId(), l.lenderId()).isPresent()) {
            events.publishEvent(new SeatChanged(tenantId, l.subscriptionId(), l.lenderId(), true));
            notices.send(tenantId, l.lenderId(), "Лицензия " + l.product() + " вернулась к вам", "/cabinet/licenses",
                    "good");
        } else if (active) {
            notices.send(tenantId, l.lenderId(), "Лицензия " + l.product() + " вернулась в общий пул: свободного "
                    + "места сейчас нет. Понадобится — запросите в кабинете", "/cabinet/licenses", "info");
        }
        events.publishEvent(ActionTaken.of(tenantId, "license.lease.ended", "license_assignment", l.licenseId(),
                ActionTaken.details("employeeId", l.employeeId(), "lenderId", l.lenderId())));
    }

    private void take(UUID tenantId, UUID subscriptionId, UUID employeeId, UUID licenseId) {
        if (pool.reclaim(licenseId)) {
            events.publishEvent(new SeatChanged(tenantId, subscriptionId, employeeId, false));
        }
    }

    private static String money(BigDecimal amount, String currency) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + ("RUB".equals(currency) ? " ₽" : " " + currency);
    }
}
