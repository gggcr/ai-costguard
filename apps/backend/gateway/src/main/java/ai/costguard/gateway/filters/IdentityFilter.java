package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.plans.PlanGate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 1: the key is valid, the agent is not suspended, and the company's plan lets it spend: not read-only and
 * operations of the month left (ADR 0033).
 */
@Component
public class IdentityFilter implements CheckStep {

    private final AgentDirectory agents;
    private final PlanGate plans;

    public IdentityFilter(AgentDirectory agents, PlanGate plans) {
        this.agents = agents;
        this.plans = plans;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        if (op.secret() == null || op.secret().isBlank()) {
            return Mono.just(new StepResult.Refuse(401, "invalid_api_key"));
        }
        var agent = agents.byKey(op.secret());
        if (agent.isEmpty()) {
            return Mono.just(new StepResult.Refuse(401, "invalid_api_key"));
        }
        op.agent(agent.get());
        if (agent.get().getSuspended()) {
            return Mono.just(new StepResult.Refuse(423, "agent_suspended"));
        }
        return plans.admit(op);
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
