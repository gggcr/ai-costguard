package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 2 of the check ladder: the mandate allows the model, vendor, amount and time.
 *
 * <p>Stub. It refuses rather than passes, so an unfinished check cannot allow spending.
 */
@Component
public class MandateFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("MandateFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
