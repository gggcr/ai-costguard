package ai.costguard.gateway.ladder;

import org.springframework.core.Order;
import reactor.core.pulisher.Mono;

public interface CheckStep extends Ordered
    Mono<StepResult> check (OperationContext op);