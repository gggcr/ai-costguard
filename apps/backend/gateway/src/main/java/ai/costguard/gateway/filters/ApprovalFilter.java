package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.events.ApprovalOutcome;
import ai.costguard.gateway.approvals.ApprovalBoard;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 7: an operation that needs a human passes only once approved, for at most the approved amount. */
@Component
public class ApprovalFilter implements CheckStep {

    private final ApprovalBoard approvals;

    public ApprovalFilter(ApprovalBoard approvals) {
        this.approvals = approvals;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        if (!op.needsApproval()) {
            return Mono.just(new StepResult.Pass());
        }
        return Mono.just(approvals.decision(op.operationId()).<StepResult>map(d -> {
            if (d.getStatus() != ApprovalOutcome.APPROVED) {
                return new StepResult.Refuse(403, "approval_rejected");
            }
            return op.estimatedCost().compareTo(d.getAmount()) <= 0 ? new StepResult.Pass()
                    : new StepResult.RequireApproval("amount_above_approved");
        }).orElseGet(() -> new StepResult.RequireApproval(op.approvalReason())));
    }

    @Override
    public int getOrder() {
        return 700;
    }
}
