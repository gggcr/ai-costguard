package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 7 of the check ladder: a human approves the operation.
 *
 * <p>Stub. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
@Component
public class ApprovalFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("ApprovalFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 700;
    }
}
