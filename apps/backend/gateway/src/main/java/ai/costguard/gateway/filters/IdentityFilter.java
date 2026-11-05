package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 1 of the check ladder: the key is valid and the agent is not suspended.
 *
 * <p>Stub. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
@Component
public class IdentityFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("IdentityFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
