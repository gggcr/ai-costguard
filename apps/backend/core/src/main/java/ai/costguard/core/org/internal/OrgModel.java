package ai.costguard.core.org.internal;

import ai.costguard.core.org.DismissalChecklistSection;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code employee}: only «Мой кабинет», no company data (ADR 0051). */
enum UserRole { owner, finance, it_admin, agent_owner, auditor, employee }

record Company(UUID id, String name, String currency, String plan) {
}

record CompanySettings(@NotBlank String name, @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}

record Department(UUID id, String name) {
}

record NewDepartment(@NotBlank String name) {
}

/** {@code erased}: the person's data was erased (ADR 0021); the row stays so history pointing at it stays whole. */
record Employee(UUID id, String name, String email, UUID departmentId, String position, boolean active,
        LocalDate dismissedAt, boolean erased) {
}

record EmployeeDetails(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotNull UUID departmentId,
        @NotBlank String position) {
}

record UserAccount(UUID id, String name, String email, UserRole role, UUID employeeId) {
}

record NewUser(@NotBlank String name, @NotBlank @Email String email, @NotNull UserRole role, UUID employeeId) {
}

record RoleChange(@NotNull UserRole role) {
}

record ChecklistSection(String name, List<DismissalChecklistSection.Item> items, BigDecimal totalCost) {
}

record DismissalChecklist(UUID employeeId, boolean dismissed, List<ChecklistSection> sections) {
}
