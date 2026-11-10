package ai.costguard.core.agents;

import java.util.List;
import java.util.UUID;

/**
 * The detector moved an agent or one of its tasks to another action: NORMAL, SLOW_DOWN, REQUIRE_APPROVAL or STOP.
 * {@code ownerId} is the employee who answers for the agent; {@code taskId} is null for the agent itself.
 */
public record AgentRiskChanged(UUID tenantId, UUID agentId, String agentName, UUID ownerId, String taskId,
        int score, String action, String previousAction, List<String> reasons) {

    /** STOP, REQUIRE_APPROVAL, SLOW_DOWN, NORMAL from the strictest down. */
    public static int rank(String action) {
        return switch (action) {
            case "STOP" -> 3;
            case "REQUIRE_APPROVAL" -> 2;
            case "SLOW_DOWN" -> 1;
            default -> 0;
        };
    }
}
