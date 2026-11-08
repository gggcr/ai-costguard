package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 3: the same operation id was already settled or is in flight. The detector catches loops by content. */
@Component
public class LoopFilter implements CheckStep {

    private final ReactiveStringRedisTemplate redis;

    public LoopFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        String prefix = "cg:{" + op.tenantId() + "}:";
        return redis.hasKey(prefix + "s:" + op.operationId())
                .zipWith(redis.hasKey(prefix + "r:" + op.operationId()), Boolean::logicalOr)
                .map(seen -> seen ? new StepResult.Refuse(409, "duplicate_operation") : new StepResult.Pass());
    }

    @Override
    public int getOrder() {
        return 300;
    }
}
