package ai.costguard.core.org.internal;

import ai.costguard.core.org.AmountsInCurrency;
import ai.costguard.core.org.CompanyTimeZoneChanged;
import ai.costguard.core.org.DismissalChecklistSection;
import ai.costguard.core.org.EmployeeDismissed;
import ai.costguard.core.plans.Limit;
import ai.costguard.core.plans.PlanLimits;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.shared.names.Language;
import ai.costguard.core.shared.names.TimeZones;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgService {

    private final OrgRepository org;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final List<DismissalChecklistSection> checklistSections;
    private final PlanLimits plans;
    private final IdentityProvider accounts;
    private final RegistrationRepository registrations;
    private final List<AmountsInCurrency> amounts;

    OrgService(OrgRepository org, TenantSession session, Clock clock, ApplicationEventPublisher events,
            List<DismissalChecklistSection> checklistSections, PlanLimits plans, IdentityProvider accounts,
            RegistrationRepository registrations, List<AmountsInCurrency> amounts) {
        this.org = org;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.checklistSections = checklistSections;
        this.plans = plans;
        this.accounts = accounts;
        this.registrations = registrations;
        this.amounts = amounts;
    }

    @Transactional(readOnly = true)
    Company company(UUID tenantId) {
        session.bind(tenantId);
        return org.company().orElseThrow(OrgProblem.COMPANY_NOT_FOUND::failure);
    }

    /**
     * The currency changes only while nothing is kept in it yet (ADR 0072): amounts without a currency of their own
     * are the company's, and a new currency would not convert them.
     */
    @Transactional
    Company saveCompany(UUID tenantId, CompanySettings settings) {
        session.bind(tenantId);
        boolean newCurrency = org.company().filter(c -> !c.currency().strip().equals(settings.currency())).isPresent();
        if (newCurrency && amounts.stream().anyMatch(AmountsInCurrency::holdsAmounts)) {
            throw OrgProblem.CURRENCY_IN_USE.failure();
        }
        if (settings.timeZone() != null && TimeZones.parse(settings.timeZone()).isEmpty()) {
            throw OrgProblem.TIME_ZONE_UNKNOWN.failure();
        }
        if (settings.language() != null && Language.parse(settings.language()).isEmpty()) {
            throw OrgProblem.LANGUAGE_UNKNOWN.failure();
        }
        String zoneBefore = org.company().map(Company::timeZone).orElse(null);
        org.saveCompany(tenantId, settings);
        String zoneAfter = org.company().map(Company::timeZone).orElse(null);
        if (zoneAfter != null && !zoneAfter.equals(zoneBefore)) {
            events.publishEvent(new CompanyTimeZoneChanged(tenantId, zoneAfter));
        }
        events.publishEvent(ActionTaken.of(tenantId, "company.saved", "company", tenantId,
                Map.of("currency", settings.currency())));
        return org.company().orElseThrow(OrgProblem.COMPANY_NOT_FOUND::failure);
    }

    @Transactional(readOnly = true)
    List<Department> departments(UUID tenantId) {
        session.bind(tenantId);
        return org.departments();
    }

    @Transactional
    Department createDepartment(UUID tenantId, NewDepartment request) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            org.insertDepartment(tenantId, id, request.name());
        } catch (DuplicateKeyException e) {
            throw OrgProblem.DEPARTMENT_NAME_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "department.created", "department", id, Map.of()));
        return new Department(id, request.name(), null);
    }

    /** The head decides on the vacations of the department (ADR 0053); null clears it. */
    @Transactional
    Department setHead(UUID tenantId, UUID departmentId, UUID employeeId) {
        session.bind(tenantId);
        requireDepartment(departmentId);
        if (employeeId != null) {
            Employee head = found(employeeId);
            if (!head.active()) {
                throw OrgProblem.EMPLOYEE_NOT_FOUND.failure();
            }
        }
        org.setHead(departmentId, employeeId);
        events.publishEvent(ActionTaken.of(tenantId, "department.head", "department", departmentId,
                ActionTaken.details("employeeId", employeeId)));
        return org.departments().stream().filter(d -> d.id().equals(departmentId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    List<Employee> employees(UUID tenantId, UUID departmentId) {
        session.bind(tenantId);
        return org.employees(departmentId);
    }

    @Transactional(readOnly = true)
    Employee employee(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return found(id);
    }

    private Employee found(UUID id) {
        return org.employee(id).orElseThrow(OrgProblem.EMPLOYEE_NOT_FOUND::failure);
    }

    @Transactional
    Employee createEmployee(UUID tenantId, EmployeeDetails details) {
        session.bind(tenantId);
        requireDepartment(details.departmentId());
        plans.requireRoom(tenantId, Limit.EMPLOYEES, org::countActiveEmployees);
        UUID id = UUID.randomUUID();
        try {
            org.insertEmployee(tenantId, id, details);
        } catch (DuplicateKeyException e) {
            throw OrgProblem.EMAIL_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "employee.created", "employee", id,
                Map.of("departmentId", details.departmentId())));
        return found(id);
    }

    @Transactional
    Employee updateEmployee(UUID tenantId, UUID id, EmployeeDetails details) {
        session.bind(tenantId);
        if (found(id).erased()) {
            throw OrgProblem.EMPLOYEE_ERASED.failure();
        }
        requireDepartment(details.departmentId());
        try {
            org.updateEmployee(id, details);
        } catch (DuplicateKeyException e) {
            throw OrgProblem.EMAIL_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "employee.updated", "employee", id,
                Map.of("departmentId", details.departmentId())));
        return found(id);
    }

    private void requireDepartment(UUID id) {
        if (!org.departmentExists(id)) {
            throw OrgProblem.DEPARTMENT_NOT_FOUND.failure();
        }
    }

    /** Repeating the call changes nothing and publishes nothing. */
    @Transactional
    Employee dismiss(UUID tenantId, UUID id) {
        return dismiss(tenantId, id, null);
    }

    /** {@code system} names who dismissed when no person did: the HR system a connector reads. */
    @Transactional
    Employee dismiss(UUID tenantId, UUID id, String system) {
        session.bind(tenantId);
        Employee employee = org.employeeForUpdate(id).orElseThrow(OrgProblem.EMPLOYEE_NOT_FOUND::failure);
        if (employee.active()) {
            LocalDate today = LocalDate.now(clock);
            org.dismiss(id, today);
            events.publishEvent(new EmployeeDismissed(tenantId, id, today));
            events.publishEvent(new ActionTaken(tenantId, "employee.dismissed", "employee", id,
                    Map.of("dismissedAt", today.toString()), system));
        }
        return found(id);
    }

    @Transactional(readOnly = true)
    DismissalChecklist checklist(UUID tenantId, UUID id) {
        session.bind(tenantId);
        Employee employee = found(id);
        List<ChecklistSection> sections = checklistSections.stream().map(section -> {
            List<DismissalChecklistSection.Item> items = section.itemsOf(id);
            BigDecimal total = items.stream().map(DismissalChecklistSection.Item::cost).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ChecklistSection(section.name(), items, total);
        }).toList();
        return new DismissalChecklist(id, !employee.active(), sections);
    }

    @Transactional(readOnly = true)
    List<UserAccount> users(UUID tenantId) {
        session.bind(tenantId);
        return org.users();
    }

    @Transactional
    UserAccount createUser(UUID tenantId, NewUser request) {
        session.bind(tenantId);
        if (request.employeeId() != null) {
            found(request.employeeId());
        }
        UUID id = UUID.randomUUID();
        try {
            org.insertUser(tenantId, id, request);
        } catch (DuplicateKeyException e) {
            throw OrgProblem.EMAIL_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "user.created", "user", id,
                ActionTaken.details("role", request.role().name(), "employeeId", request.employeeId())));
        return org.user(id).orElseThrow(OrgProblem.USER_NOT_FOUND::failure);
    }

    @Transactional
    UserAccount changeRole(UUID tenantId, UUID id, UserRole role) {
        session.bind(tenantId);
        long owners = org.lockOwners();
        UserAccount user = org.user(id).orElseThrow(OrgProblem.USER_NOT_FOUND::failure);
        if (user.role() == UserRole.owner && role != UserRole.owner && owners == 1) {
            throw OrgProblem.LAST_OWNER.failure();
        }
        org.setRole(id, role);
        if (user.role() != role) {
            events.publishEvent(ActionTaken.of(tenantId, "user.role_changed", "user", id,
                    Map.of("from", user.role().name(), "to", role.name())));
        }
        return org.user(id).orElseThrow(OrgProblem.USER_NOT_FOUND::failure);
    }

    /** Closes a user's access. The last owner stays: someone must be able to manage the company. */
    @Transactional
    void removeUser(UUID tenantId, UUID id) {
        session.bind(tenantId);
        long owners = org.lockOwners();
        UserAccount user = org.user(id).orElseThrow(OrgProblem.USER_NOT_FOUND::failure);
        if (user.role() == UserRole.owner && owners == 1) {
            throw OrgProblem.LAST_OWNER.failure();
        }
        // An account created by an invitation goes too, or the person could still log in (ADR 0036). Deleted
        // before the row: if Keycloak fails, nothing changes and the call can be repeated.
        var account = registrations.accountOfUser(id);
        if (account.isPresent() && accounts.configured()) {
            try {
                accounts.delete(account.get());
            } catch (IdentityProvider.Unavailable e) {
                throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
            }
        }
        org.deleteUser(id);
        events.publishEvent(ActionTaken.of(tenantId, "user.removed", "user", id,
                Map.of("role", user.role().name())));
    }
}
