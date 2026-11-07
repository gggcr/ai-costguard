package ai.costguard.core.org;

import java.time.LocalDate;
import java.util.UUID;

/** Published once, when an employee is dismissed. */
public record EmployeeDismissed(UUID tenantId, UUID employeeId, LocalDate dismissedAt) {
}
