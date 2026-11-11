package ai.costguard.core.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Who heads whom (ADR 0053). Call inside a transaction already bound to the tenant. */
public interface DepartmentHeads {

    /** The head of the employee's department; empty when it has none or the employee is the head. */
    Optional<UUID> headOf(UUID employeeId);

    /** The active employees of the departments this employee heads, the head left out. */
    List<UUID> headedBy(UUID headEmployeeId);
}
