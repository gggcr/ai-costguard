package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** The employee of the token, for every /api/v1/me endpoint. Call inside a transaction bound to the tenant. */
@Component
class Whoami {

    private final UserDirectory users;
    private final EmployeeDirectory employees;

    Whoami(UserDirectory users, EmployeeDirectory employees) {
        this.users = users;
        this.employees = employees;
    }

    /** The active employee of the token; a person who is not one, or was dismissed, gets 404 not_an_employee. */
    UUID employee(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return users.userByEmail(email).map(UserDirectory.UserCard::employeeId)
                .or(() -> employees.employeeOfUser(email))
                .or(() -> employees.employeeByEmail(email))
                .flatMap(employees::card)
                .filter(c -> c.dismissedAt() == null)
                .map(EmployeeDirectory.EmployeeCard::id)
                .orElseThrow(CabinetProblem.NOT_AN_EMPLOYEE::failure);
    }

    static String who(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");
        return name != null && !name.isBlank() ? name : email != null ? email : jwt.getSubject();
    }
}
