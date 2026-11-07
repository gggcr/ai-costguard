package ai.costguard.core.org.internal;

import ai.costguard.core.org.DismissalChecklistSection;
import ai.costguard.core.org.EmployeeDismissed;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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

    OrgService(OrgRepository org, TenantSession session, Clock clock, ApplicationEventPublisher events,
            List<DismissalChecklistSection> checklistSections) {
        this.org = org;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.checklistSections = checklistSections;
    }

    @Transactional(readOnly = true)
    Company company(UUID tenantId) {
        session.bind(tenantId);
        return org.company().orElseThrow(OrgProblem.COMPANY_NOT_FOUND::failure);
    }

    @Transactional
    Company saveCompany(UUID tenantId, CompanySettings settings) {
        session.bind(tenantId);
        org.saveCompany(tenantId, settings);
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
        return new Department(id, request.name());
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
        UUID id = UUID.randomUUID();
        try {
            org.insertEmployee(tenantId, id, details);
        } catch (DuplicateKeyException e) {
            throw OrgProblem.EMAIL_TAKEN.failure();
        }
        return found(id);
    }

    @Transactional
    Employee updateEmployee(UUID tenantId, UUID id, EmployeeDetails details) {
        session.bind(tenantId);
        found(id);
        requireDepartment(details.departmentId());
        try {
            org.updateEmployee(id, details);
        } catch (DuplicateKeyException e) {
            throw OrgProblem.EMAIL_TAKEN.failure();
        }
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
        session.bind(tenantId);
        Employee employee = org.employeeForUpdate(id).orElseThrow(OrgProblem.EMPLOYEE_NOT_FOUND::failure);
        if (employee.active()) {
            LocalDate today = LocalDate.now(clock);
            org.dismiss(id, today);
            events.publishEvent(new EmployeeDismissed(tenantId, id, today));
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
        return org.user(id).orElseThrow(OrgProblem.USER_NOT_FOUND::failure);
    }
}
