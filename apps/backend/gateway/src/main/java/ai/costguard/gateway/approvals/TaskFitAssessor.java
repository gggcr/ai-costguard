package ai.costguard.gateway.approvals;

import ai.costguard.gateway.ladder.Operation;
import reactor.core.publisher.Mono;

/**
 * The AI assessor of step 6: does a large operation fit the goal of its task? An implementation that is unsure
 * must answer no, so a human decides.
 */
public interface TaskFitAssessor {

    Mono<Boolean> fits(Operation op);
}
