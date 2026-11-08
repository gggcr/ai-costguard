package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.risk.RiskBoard;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 4: the detector's latest signal for the agent or its task. Runs before the reserve. */
@Component
public class RiskFilter implements CheckStep {

    private final RiskBoard risk;

    public RiskFilter(RiskBoard risk) {
        this.risk = risk;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        return Mono.just(switch (risk.action(op.agentId(), op.taskId())) {
            case STOP -> new StepResult.Refuse(423, "agent_suspended");
            case SLOW_DOWN -> new StepResult.Refuse(429, "rate_limited");
            case REQUIRE_APPROVAL -> {
                op.needApproval("risk");
                yield new StepResult.Pass();
            }
            case NORMAL -> new StepResult.Pass();
        });
    }

    @Override
    public int getOrder() {
        return 400;
    }
}
