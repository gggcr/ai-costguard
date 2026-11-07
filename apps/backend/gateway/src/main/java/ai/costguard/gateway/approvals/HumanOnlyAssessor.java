package ai.costguard.gateway.approvals;

import ai.costguard.gateway.ladder.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Used while no AI assessor is configured: every operation above the threshold goes to a human. Agents send no
 * task goal yet, and an assessor without the goal would only guess.
 */
@Component
@ConditionalOnMissingBean(value = TaskFitAssessor.class, ignored = HumanOnlyAssessor.class)
public class HumanOnlyAssessor implements TaskFitAssessor {

    @Override
    public Mono<Boolean> fits(Operation op) {
        return Mono.just(false);
    }
}
