package ai.costguard.core.cabinet.internal;

import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.DepartmentHeads;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отпуска (ADR 0053): an employee asks, the head of their department decides; the employer sees all and decides
 * where a department has no head. Days are calendar days, as ТК РФ counts annual leave.
 */
@Service
class VacationService {

    static final int DAYS_PER_YEAR = 28;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final VacationRepository vacations;
    private final DepartmentHeads heads;
    private final EmployeeDirectory employees;
    private final PersonalNotices notices;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    VacationService(VacationRepository vacations, DepartmentHeads heads, EmployeeDirectory employees,
            PersonalNotices notices, Whoami whoami, TenantSession session, ApplicationEventPublisher events,
            Clock clock) {
        this.vacations = vacations;
        this.heads = heads;
        this.employees = employees;
        this.notices = notices;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MOSCOW));
    }

    @Transactional(readOnly = true)
    VacationBalance mine(UUID tenantId, Jwt jwt, Integer year) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        return balance(me, year == null ? today().getYear() : year);
    }

    private VacationBalance balance(UUID employeeId, int year) {
        List<Vacation> all = vacations.of(employeeId);
        int used = annualDays(all, year, "approved");
        int pending = annualDays(all, year, "pending");
        return new VacationBalance(year, DAYS_PER_YEAR, used, pending, Math.max(0, DAYS_PER_YEAR - used - pending),
                all, heads.headOf(employeeId).isPresent());
    }

    private static int annualDays(List<Vacation> all, int year, String status) {
        return all.stream().filter(v -> v.kind().equals("annual") && v.status().equals(status)
                && v.startsOn().getYear() == year).mapToInt(Vacation::days).sum();
    }

    @Transactional
    Vacation ask(UUID tenantId, Jwt jwt, NewVacation request) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        if (request.endsOn().isBefore(request.startsOn())) {
            throw CabinetProblem.VACATION_DATES.failure();
        }
        if (request.startsOn().isBefore(today())) {
            throw CabinetProblem.VACATION_IN_PAST.failure();
        }
        int days = (int) ChronoUnit.DAYS.between(request.startsOn(), request.endsOn()) + 1;
        for (Vacation v : vacations.holding(me)) {
            if (!request.endsOn().isBefore(v.startsOn()) && !request.startsOn().isAfter(v.endsOn())) {
                throw CabinetProblem.VACATION_OVERLAP.failure();
            }
        }
        if (request.kind() == VacationKind.annual) {
            VacationBalance b = balance(me, request.startsOn().getYear());
            if (days > b.left()) {
                throw CabinetProblem.VACATION_DAYS_EXCEEDED.failure();
            }
        }
        UUID id = UUID.randomUUID();
        vacations.insert(tenantId, id, me, request, days, clock.instant());
        String name = employees.card(me).map(EmployeeDirectory.EmployeeCard::name).orElse("Сотрудник");
        heads.headOf(me).ifPresent(head -> notices.send(tenantId, head, name + " просит отпуск "
                + span(request.startsOn(), request.endsOn(), days), "/cabinet/vacations", "warning"));
        events.publishEvent(ActionTaken.of(tenantId, "vacation.requested", "vacation_request", id,
                ActionTaken.details("employeeId", me, "days", days)));
        return vacations.find(id).orElseThrow();
    }

    @Transactional
    void cancel(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        Vacation v = vacations.lock(id).filter(x -> x.employeeId().equals(me))
                .orElseThrow(CabinetProblem.VACATION_NOT_FOUND::failure);
        boolean future = v.status().equals("approved") && v.startsOn().isAfter(today());
        if (!v.status().equals("pending") && !future) {
            throw CabinetProblem.VACATION_DECIDED.failure();
        }
        vacations.decide(id, "cancelled", Whoami.who(jwt), clock.instant(), null);
        if (future) {
            heads.headOf(me).ifPresent(head -> notices.send(tenantId, head, "Отменён согласованный отпуск "
                    + span(v.startsOn(), v.endsOn(), v.days()) + " сотрудника " + v.employeeName(),
                    "/cabinet/vacations", "info"));
        }
    }

    /** The head's view: requests of the people of the departments they head. */
    @Transactional(readOnly = true)
    List<Vacation> team(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        return vacations.ofEmployees(heads.headedBy(me));
    }

    @Transactional
    Vacation decideAsHead(UUID tenantId, Jwt jwt, UUID id, VacationDecision decision) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        Vacation v = vacations.lock(id).orElseThrow(CabinetProblem.VACATION_NOT_FOUND::failure);
        if (!heads.headOf(v.employeeId()).map(me::equals).orElse(false)) {
            throw CabinetProblem.NOT_THE_HEAD.failure();
        }
        return decide(tenantId, v, decision, Whoami.who(jwt));
    }

    /** The employer's view: every request, for departments without a head and for the record. */
    @Transactional(readOnly = true)
    List<Vacation> all(UUID tenantId, VacationStatus status) {
        session.bind(tenantId);
        return vacations.all(status == null ? null : status.name());
    }

    @Transactional
    Vacation decideAsEmployer(UUID tenantId, UUID id, VacationDecision decision, String by) {
        session.bind(tenantId);
        Vacation v = vacations.lock(id).orElseThrow(CabinetProblem.VACATION_NOT_FOUND::failure);
        return decide(tenantId, v, decision, by);
    }

    private Vacation decide(UUID tenantId, Vacation v, VacationDecision decision, String by) {
        if (!v.status().equals("pending")) {
            throw CabinetProblem.VACATION_DECIDED.failure();
        }
        String status = decision.approve() ? "approved" : "rejected";
        vacations.decide(v.id(), status, by, clock.instant(), decision.comment());
        String text = (decision.approve() ? "Отпуск согласован: " : "Отпуск не согласован: ")
                + span(v.startsOn(), v.endsOn(), v.days())
                + (decision.comment() == null || decision.comment().isBlank() ? "" : ". " + decision.comment().strip());
        notices.send(tenantId, v.employeeId(), text, "/cabinet/vacations", decision.approve() ? "good" : "warning");
        events.publishEvent(ActionTaken.of(tenantId, "vacation." + status, "vacation_request", v.id(),
                ActionTaken.details("employeeId", v.employeeId())));
        return vacations.find(v.id()).orElseThrow();
    }

    private static String span(LocalDate from, LocalDate to, int days) {
        return DAY.format(from) + "–" + DAY.format(to) + " (" + days + " дн.)";
    }
}
