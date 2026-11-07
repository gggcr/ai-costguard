package ai.costguard.core.agents;

import java.util.Optional;
import java.util.UUID;

/** Who answers for an agent. Call inside a transaction already bound to the tenant. */
public interface AgentOwners {

    Optional<UUID> ownerOf(UUID agentId);
}
