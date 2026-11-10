package ai.costguard.gateway.plans;

import ai.costguard.events.TenantPlan;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The plan's part of the identity step (ADR 0033): a read-only company spends nothing, and a company that used up
 * its operations of the month waits for the next one or a bigger plan. Both answer 402, with a message that says
 * what to do.
 */
@Component
public class PlanGate {

    static final String READ_ONLY = "The company's trial or paid period has ended: pay for a plan in CostGuard to "
            + "resume operations";
    static final String USED_UP = "The company's plan allows %d operations a month and they are used up: move to a "
            + "bigger plan in CostGuard or wait for the next month";

    private final PlanBoard plans;
    private final OperationQuota quota;

    public PlanGate(PlanBoard plans, OperationQuota quota) {
        this.plans = plans;
        this.quota = quota;
    }

    /** Call once the operation knows its agent. A company without a plan passes without counting. */
    public Mono<StepResult> admit(Operation op) {
        Optional<TenantPlan> found = plans.of(op.tenantId());
        if (found.isEmpty()) {
            return Mono.just(new StepResult.Pass());
        }
        TenantPlan plan = found.get();
        if (plan.getReadOnly()) {
            return Mono.just(new StepResult.Refuse(402, "plan_read_only", READ_ONLY));
        }
        Long limit = plan.getOperationsPerMonth();
        if (limit == null) {
            return Mono.just(new StepResult.Pass());
        }
        String month = OperationQuota.month(op.at());
        return quota.take(op.tenantId(), month, limit).map(took -> {
            if (!took) {
                return new StepResult.Refuse(402, "plan_limit_exceeded", USED_UP.formatted(limit));
            }
            op.countedIn(month);
            return new StepResult.Pass();
        });
    }

    /** The operation was refused or held after it took a place: the place goes back. */
    public Mono<Void> giveBack(Operation op) {
        if (op.countedIn() == null || op.tenantId() == null) {
            return Mono.empty();
        }
        String month = op.countedIn();
        op.countedIn(null);
        return quota.giveBack(op.tenantId(), month);
    }
}
