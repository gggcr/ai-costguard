package ai.costguard.gateway.ladder;

import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class CheckLadder {

    static final int STEPS = 7;
    private final List<CheckStep> steps;

    public CheckLadder(List<CheckStep> steps) {
        if (steps.size() != STEPS) {
            throw new IllegalStateException("Expected " + STEPS + " check steps, got " + steps.size());
        }
        this.steps = steps.stream().sorted(Comparator.comparingInt(Ordered::getOrder)).toList();
    }

    public Mono<StepResult> run(OperationContext op) {
        return Flux.fromIterable(steps)
                .concatMap(step -> step.check(op))
                .filter(r -> !(r instanceof StepResult.Pass))
                .next()
                .defaultIfEmpty(new StepResult.Pass());
    }
}