package ai.costguard.core.shared.inbox;

import ai.costguard.core.agents.AgentRisks;
import ai.costguard.events.AgentRiskSignal;
import java.util.List;

/**
 * Risk of agents and tasks from the anomaly detector, {@code agent.risk-signals.v1} (ADR 0031). The topic is
 * compacted: a tombstone (an erased agent) has no value and is skipped.
 */
class RiskSignalInbox {

    private final AgentRisks risks;

    RiskSignalInbox(AgentRisks risks) {
        this.risks = risks;
    }

    void handle(AgentRiskSignal s) {
        risks.signal(s.getTenantId(), s.getAgentId(), s.getTaskId(), s.getScore(), s.getAction().name(),
                List.copyOf(s.getReasons()), s.getDetectedAt());
    }
}
