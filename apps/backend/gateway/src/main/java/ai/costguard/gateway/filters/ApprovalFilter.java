package ai.costguard.gateway.filters;

import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.ApprovalOutcome;
import ai.costguard.gateway.approvals.ApprovalBoard;
import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 7 (board frame 3): an operation that needs a human passes only once approved, for at most the approved
 * amount. The approval is a one-time permission to retry: the retry has run every step before this one, the reserve
 * included, and it is used up here. It lasts {@code costguard.gateway.approval-ttl} from the decision; a later retry,
 * or a second one, is refused (ADR 0020).
 */
@Component
public class ApprovalFilter implements CheckStep {

    private final ApprovalBoard approvals;
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final Duration ttl;

    public ApprovalFilter(ApprovalBoard approvals, ReactiveStringRedisTemplate redis, Clock clock,
            @Value("${costguard.gateway.approval-ttl:PT1H}") Duration ttl) {
        this.approvals = approvals;
        this.redis = redis;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        if (!op.needsApproval()) {
            return Mono.just(new StepResult.Pass());
        }
        return approvals.decision(op.operationId()).map(d -> decided(op, d))
                .orElseGet(() -> Mono.just(new StepResult.RequireApproval(op.approvalReason())));
    }

    private Mono<StepResult> decided(Operation op, ApprovalDecision d) {
        if (d.getStatus() != ApprovalOutcome.APPROVED) {
            return Mono.just(new StepResult.Refuse(403, "approval_rejected"));
        }
        if (op.estimatedCost().compareTo(d.getAmount()) > 0) {
            return Mono.just(new StepResult.RequireApproval("amount_above_approved"));
        }
        Duration left = Duration.between(clock.instant(), d.getDecidedAt().plus(ttl));
        if (left.isNegative() || left.isZero()) {
            return Mono.just(new StepResult.Refuse(403, "approval_expired"));
        }
        // SET NX: of two retries at once, across gateway replicas, only one uses the permission.
        String key = "cg:{" + op.tenantId() + "}:a:" + op.operationId();
        return redis.opsForValue().setIfAbsent(key, d.getApprovalId().toString(), left)
                .map(first -> first ? new StepResult.Pass() : new StepResult.Refuse(409, "approval_used"));
    }

    @Override
    public int getOrder() {
        return 700;
    }
}
