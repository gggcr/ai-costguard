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
}
