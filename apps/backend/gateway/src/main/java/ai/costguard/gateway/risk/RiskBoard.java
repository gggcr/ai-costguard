package ai.costguard.gateway.risk;

import ai.costguard.events.AgentRiskSignal;
import ai.costguard.events.RiskAction;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The detector's latest verdict per agent and per task. No signal means normal. */
@Component
public class RiskBoard {

    private final Map<String, AgentRiskSignal> signals = new ConcurrentHashMap<>();

    private static String key(UUID agentId, String taskId) {
        return taskId == null ? "agent:" + agentId : "task:" + agentId + ":" + taskId;
    }

    public void apply(AgentRiskSignal signal) {
        signals.merge(key(signal.getAgentId(), signal.getTaskId()), signal,
                (old, fresh) -> fresh.getDetectedAt().isBefore(old.getDetectedAt()) ? old : fresh);
    }

    /** The stricter of the agent's and the task's signal. */
    public RiskAction action(UUID agentId, String taskId) {
        RiskAction agent = level(signals.get(key(agentId, null)));
        RiskAction task = taskId == null ? RiskAction.NORMAL : level(signals.get(key(agentId, taskId)));
        return agent.ordinal() >= task.ordinal() ? agent : task;
    }

    private static RiskAction level(AgentRiskSignal signal) {
        return signal == null ? RiskAction.NORMAL : signal.getAction();
    }
}
