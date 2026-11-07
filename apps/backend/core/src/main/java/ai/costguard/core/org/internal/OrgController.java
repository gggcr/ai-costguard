package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organisation", description = "Company, departments, employees, users and dismissals")
class OrgController {

    private final OrgService service;

    OrgController(OrgService service) {
        this.service = service;
    }

    @GetMapping("/company")
    @Operation(summary = "Get the company of the current token")
    Company company(@AuthenticationPrincipal Jwt jwt) {
        return service.company(TenantClaim.of(jwt));
    }

    @PutMapping("/company")
    @ManagesOrg
    @Operation(summary = "Create or update the company", description = "Idempotent.")
    Company saveCompany(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompanySettings settings) {
        return service.saveCompany(TenantClaim.of(jwt), settings);
    }

    @GetMapping("/departments")
    @Operation(summary = "List departments")
    List<Department> departments(@AuthenticationPrincipal Jwt jwt) {
        return service.departments(TenantClaim.of(jwt));
    }

    @PostMapping("/departments")
    @ManagesOrg
    @Operation(summary = "Create a department")
    ResponseEntity<Department> createDepartment(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewDepartment request) {
        Department created = service.createDepartment(TenantClaim.of(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/departments/" + created.id())).body(created);
    }

    @GetMapping("/employees")
    @Operation(summary = "List employees, optionally of one department")
    List<Employee> employees(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID departmentId) {
        return service.employees(TenantClaim.of(jwt), departmentId);
    }

    @PostMapping("/employees")
    @ManagesOrg
    @Operation(summary = "Add an employee")
    ResponseEntity<Employee> createEmployee(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EmployeeDetails details) {
        Employee created = service.createEmployee(TenantClaim.of(jwt), details);
        return ResponseEntity.created(URI.create("/api/v1/employees/" + created.id())).body(created);
    }

    @GetMapping("/employees/{id}")
    @Operation(summary = "Get an employee")
    Employee employee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.employee(TenantClaim.of(jwt), id);
    }

    @PutMapping("/employees/{id}")
    @ManagesOrg
    @Operation(summary = "Update an employee")
    Employee updateEmployee(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody EmployeeDetails details) {
        return service.updateEmployee(TenantClaim.of(jwt), id, details);
    }

    @PutMapping("/employees/{id}/dismissal")
    @ManagesOrg
    @Operation(summary = "Mark an employee as dismissed", description = "Idempotent.")
    Employee dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.dismiss(TenantClaim.of(jwt), id);
    }

    @GetMapping("/employees/{id}/dismissal-checklist")
    @Operation(summary = "What must be collected or revoked from an employee")
    DismissalChecklist checklist(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.checklist(TenantClaim.of(jwt), id);
    }

    @GetMapping("/users")
    @Operation(summary = "List CostGuard users")
    List<UserAccount> users(@AuthenticationPrincipal Jwt jwt) {
        return service.users(TenantClaim.of(jwt));
    }

    @PostMapping("/users")
    @ManagesOrg
    @Operation(summary = "Add a CostGuard user")
    ResponseEntity<UserAccount> createUser(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewUser request) {
        UserAccount created = service.createUser(TenantClaim.of(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PutMapping("/users/{id}/role")
    @ManagesOrg
    @Operation(summary = "Change a user's role", description = "The last owner cannot be demoted.")
    UserAccount changeRole(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RoleChange change) {
        return service.changeRole(TenantClaim.of(jwt), id, change.role());
    }
}
