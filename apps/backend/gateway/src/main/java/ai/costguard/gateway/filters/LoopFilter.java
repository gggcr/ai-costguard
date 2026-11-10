package ai.costguard.gateway.filters;

import ai.costguard.gateway.answers.AnswerCache;
import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Step 3: the same operation id was already settled or is in flight. When its answer was kept (AnswerCache), the
 * retry gets it again; otherwise, the first call still running or its answer too big to keep, 409. The detector
 * catches loops by content.
 */
@Component
public class LoopFilter implements CheckStep {

    private final ReactiveStringRedisTemplate redis;
    private final AnswerCache answers;

    public LoopFilter(ReactiveStringRedisTemplate redis, AnswerCache answers) {
        this.redis = redis;
        this.answers = answers;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        String prefix = "cg:{" + op.tenantId() + "}:";
        return redis.hasKey(prefix + "s:" + op.operationId())
                .zipWith(redis.hasKey(prefix + "r:" + op.operationId()), Boolean::logicalOr)
                .flatMap(seen -> !seen ? Mono.just((StepResult) new StepResult.Pass())
                        : answers.find(op.tenantId(), op.operationId())
                                .map(answer -> (StepResult) new StepResult.Answered(answer))
                                .defaultIfEmpty(new StepResult.Refuse(409, "duplicate_operation")));
    }

    @Override
    public int getOrder() {
        return 300;
    }
}
