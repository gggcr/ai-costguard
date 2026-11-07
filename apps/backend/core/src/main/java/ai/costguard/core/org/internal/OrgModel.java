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

enum UserRole { owner, finance, it_admin, agent_owner, auditor }

record Company(UUID id, String name, String currency, String plan) {
}

record CompanySettings(@NotBlank String name, @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}

record Department(UUID id, String name) {
}

record NewDepartment(@NotBlank String name) {
}

record Employee(UUID id, String name, String email, UUID departmentId, String position, boolean active,
        LocalDate dismissedAt) {
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
