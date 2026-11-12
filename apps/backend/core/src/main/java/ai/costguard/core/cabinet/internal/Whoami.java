package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The person of the token, for every /api/v1/me endpoint. Call inside a transaction bound to the tenant of the token,
 * so the account and the employee found are of that company (RLS).
 *
 * <p>The person is the CostGuard account linked to the subject (sub) of the verified token, and their employee is the
 * one that account is linked to. The token's e-mail decides nothing: a person can change it in their Keycloak
 * account, so an e-mail would let them be someone else (the e-mail spoofing finding of 2026-11-12).
 */
@Component
class Whoami {

    private final UserDirectory users;
    private final EmployeeDirectory employees;

    Whoami(UserDirectory users, EmployeeDirectory employees) {
        this.users = users;
        this.employees = employees;
    }

    /** The account of the token; a token no account is linked to gets 404 not_an_employee. */
    UserDirectory.UserCard account(Jwt jwt) {
        return users.userBySubject(jwt.getSubject()).orElseThrow(CabinetProblem.NOT_AN_EMPLOYEE::failure);
    }

    /** The active employee of the token's account; without one, or dismissed, 404 not_an_employee. */
    UUID employee(Jwt jwt) {
        return users.userBySubject(jwt.getSubject())
                .map(UserDirectory.UserCard::employeeId)
                .flatMap(employees::card)
                .filter(c -> c.dismissedAt() == null)
                .map(EmployeeDirectory.EmployeeCard::id)
                .orElseThrow(CabinetProblem.NOT_AN_EMPLOYEE::failure);
    }

    /** A name to show for the token, e.g. who uploaded a file; not an identity. */
    static String who(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");
        return name != null && !name.isBlank() ? name : email != null ? email : jwt.getSubject();
    }
}
