package ai.costguard.gateway.filters;

import ai.costguard.gateway.budget.BudgetTree;
import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.plans.PlanGate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 1: the key is valid, the agent is not suspended, and the company's plan lets it spend: not read-only and
 * operations of the month left (ADR 0037).
 */
@Component
public class IdentityFilter implements CheckStep {

    private final AgentDirectory agents;
    private final PlanGate plans;
    private final BudgetTree budgets;

    public IdentityFilter(AgentDirectory agents, PlanGate plans, BudgetTree budgets) {
        this.agents = agents;
        this.plans = plans;
        this.budgets = budgets;
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
        op.currency(budgets.currency(agent.get().getTenantId()).orElse(null));
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
