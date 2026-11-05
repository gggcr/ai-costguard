package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.OperationContext;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RiskFilter implements CheckStep {

    @Override
    public Mono<StepResult> check(OperationContext op) {
        return Mono.error(new UnsupportedOperationException("RiskFilter is not implemented"));
    }

    @Override
    public int getOrder() {
        return 400;
    }
}