package ai.costguard.core.org;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Links a login, an e-mail or a name to a person. Call inside a transaction already bound to the tenant. */
public interface EmployeeDirectory {

    Optional<UUID> employeeOfUser(String email);

    /** The employee with this e-mail, ignoring case. */
    Optional<UUID> employeeByEmail(String email);

    /** The only employee with this cleaned full name; empty when there is none or several namesakes. */
    Optional<UUID> employeeByName(String name);

    /** Dismissed employees and the day each left. */
    Map<UUID, LocalDate> dismissed();

    /** Who the employee is, as documents name them; empty when the tenant has no such employee. */
    Optional<EmployeeCard> card(UUID employeeId);

    /** The company as documents name it; empty until its settings are saved. */
    Optional<CompanyCard> company();

    record EmployeeCard(UUID id, String name, String position, String department, String email,
            LocalDate dismissedAt) {
    }

    record CompanyCard(String name, String currency) {
    }
}
