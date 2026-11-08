package ai.costguard.gateway.approvals;

import ai.costguard.gateway.ladder.Operation;
import reactor.core.publisher.Mono;

/**
 * Used while no AI assessor is configured ({@link AssessorConfig}): every operation above the threshold goes to a
 * person.
 */
public class HumanOnlyAssessor implements TaskFitAssessor {

    @Override
    public Mono<Boolean> fits(Operation op) {
        return Mono.just(false);
    }
}
