package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 5 of the check ladder: the reserve succeeds at every budget level.
 *
 * <p>Stub. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
@Component
public class BudgetFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("BudgetFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 500;
    }
}
