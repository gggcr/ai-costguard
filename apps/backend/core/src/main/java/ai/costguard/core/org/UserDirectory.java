package ai.costguard.core.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The company's CostGuard users. Call inside a transaction already bound to the tenant. */
public interface UserDirectory {

    List<UserCard> users();

    /** The user with this e-mail (the token's), ignoring case. */
    Optional<UserCard> userByEmail(String email);

    /** {@code role}: owner, finance, it_admin, agent_owner or auditor; {@code employeeId} may be null. */
    record UserCard(UUID id, String name, String email, String role, UUID employeeId) {
    }
}
