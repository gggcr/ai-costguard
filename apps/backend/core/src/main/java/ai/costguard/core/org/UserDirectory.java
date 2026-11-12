package ai.costguard.core.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The company's CostGuard users. Call inside a transaction already bound to the tenant. */
public interface UserDirectory {

    List<UserCard> users();

    /**
     * The signed-in person's account, by the subject (sub) of the verified token. Never look a person up by the
     * token's e-mail: a person can change it in their Keycloak account, the subject they cannot.
     */
    Optional<UserCard> userBySubject(String subject);

    /** {@code role}: owner, finance, it_admin, agent_owner or auditor; {@code employeeId} may be null. */
    record UserCard(UUID id, String name, String email, String role, UUID employeeId) {
    }
}
