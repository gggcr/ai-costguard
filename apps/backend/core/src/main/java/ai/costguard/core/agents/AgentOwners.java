package ai.costguard.core.agents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Who answers for an agent. Call inside a transaction already bound to the tenant. */
public interface AgentOwners {

    Optional<UUID> ownerOf(UUID agentId);

    /** The agents this employee answers for, by name (ADR 0067). */
    List<OwnedAgent> ownedBy(UUID employeeId);

    /**
     * {@code status}: active or suspended; {@code riskAction}: what the gateway does after the anomaly detector —
     * NORMAL, SLOW_DOWN, REQUIRE_APPROVAL or STOP.
     */
    record OwnedAgent(UUID id, String name, String status, String riskAction, int riskScore, String riskReason) {
    }
}
