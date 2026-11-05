package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 6 of the check ladder: the AI assessor finds an operation above the mandate threshold fits the task goal.
 *
 * <p>Stub. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
@Component
public class TaskFitFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("TaskFitFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 600;
    }
}
