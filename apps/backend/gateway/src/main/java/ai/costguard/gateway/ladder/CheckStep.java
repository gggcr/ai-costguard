package ai.costguard.gateway.ladder;

import org.springframework.core.Ordered;
import reactor.core.publisher.Mono;

public interface CheckStep extends Ordered {
    Mono<StepResult> check(Operation op);
}