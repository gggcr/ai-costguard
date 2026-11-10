package ai.costguard.core.agents;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The anomaly detector's verdicts on agents and their tasks, from {@code agent.risk-signals.v1} (ADR 0031). */
public interface AgentRisks {

    /**
     * Keeps the latest risk of the agent ({@code taskId} null) or of one of its tasks, and the strictest of them on
     * the agent. An older signal than the one kept is ignored, so a replay changes nothing. When the action changes,
     * publishes {@link AgentRiskChanged}. An agent the company does not have is ignored too.
     */
    void signal(UUID tenantId, UUID agentId, String taskId, int score, String action, List<String> reasons,
            Instant detectedAt);
}
