package ai.costguard.gateway.answers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The answer of an operation that went through, in Redis next to its reserve (board frame 3: "repeat → answer from
 * the cache"). A retry with the same Idempotency-Key, after a timeout or a dropped connection, gets the same answer
 * again instead of 409 duplicate_operation, and is neither checked, reserved nor charged a second time. Kept for
 * {@code costguard.gateway.answer-ttl} (24 h); an answer above {@code costguard.gateway.answer-max-bytes} (1 MiB) is
 * not kept, and its retry still gets 409.
 */
@Component
public class AnswerCache {

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;
    private final int maxBytes;

    public AnswerCache(ReactiveStringRedisTemplate redis,
            @Value("${costguard.gateway.answer-ttl:PT24H}") Duration ttl,
            @Value("${costguard.gateway.answer-max-bytes:1048576}") int maxBytes) {
        this.redis = redis;
        this.ttl = ttl;
        this.maxBytes = maxBytes;
    }

    /** In the hash slot of the tenant, like the reserve keys. */
    public static String key(UUID tenantId, UUID operationId) {
        return "cg:{" + tenantId + "}:c:" + operationId;
    }

    public boolean fits(int bytes) {
        return bytes <= maxBytes;
    }

    /** Keeps the answer; false when it is too big to keep. */
    public Mono<Boolean> keep(UUID tenantId, UUID operationId, Answer answer) {
        if (!fits(answer.body().length)) {
            return Mono.just(false);
        }
        String value = answer.status() + "\n" + (answer.contentType() == null ? "" : answer.contentType()) + "\n"
                + Base64.getEncoder().encodeToString(answer.body());
        return redis.opsForValue().set(key(tenantId, operationId), value, ttl);
    }

    public Mono<Answer> find(UUID tenantId, UUID operationId) {
        return redis.opsForValue().get(key(tenantId, operationId)).map(AnswerCache::parse);
    }

    static Answer parse(String value) {
        String[] parts = value.split("\n", 3);
        String type = parts[1].isEmpty() ? null : parts[1];
        return new Answer(Integer.parseInt(parts[0]), type, Base64.getDecoder().decode(parts[2]
                .getBytes(StandardCharsets.US_ASCII)));
    }
}
