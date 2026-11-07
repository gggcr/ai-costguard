package ai.costguard.gateway.approvals;

import ai.costguard.events.ApprovalDecision;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Decisions from {@code approvals.decisions.v1}, by operation. */
@Component
public class ApprovalBoard {

    private final Map<UUID, ApprovalDecision> decisions = new ConcurrentHashMap<>();

    public void apply(ApprovalDecision decision) {
        decisions.put(decision.getOperationId(), decision);
    }

    public Optional<ApprovalDecision> decision(UUID operationId) {
        return Optional.ofNullable(decisions.get(operationId));
    }
}
