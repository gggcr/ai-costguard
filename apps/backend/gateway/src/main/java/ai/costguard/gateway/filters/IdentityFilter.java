package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.keys.AgentDirectory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 1: the key is valid and the agent is not suspended. */
@Component
public class IdentityFilter implements CheckStep {

    private final AgentDirectory agents;

    public IdentityFilter(AgentDirectory agents) {
        this.agents = agents;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        if (op.secret() == null || op.secret().isBlank()) {
            return Mono.just(new StepResult.Refuse(401, "invalid_api_key"));
        }
        return Mono.just(agents.byKey(op.secret()).<StepResult>map(agent -> {
            op.agent(agent);
            return agent.getSuspended() ? new StepResult.Refuse(423, "agent_suspended") : new StepResult.Pass();
        }).orElseGet(() -> new StepResult.Refuse(401, "invalid_api_key")));
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
