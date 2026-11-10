package ai.costguard.gateway.plans;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Operations of a company in the calendar month (UTC), against its plan (ADR 0033). The counter is in Redis, shared
 * by every gateway instance, under the {tenant} hash tag like the budget keys. An operation takes a place when it
 * passes the identity step and gives it back if a later step refuses or holds it, so the counter is what passed
 * the whole ladder.
 */
@Component
public class OperationQuota {

    static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    /** A month and a few days: the counter of a month outlives it only long enough to be looked at. */
    static final Duration KEEP = Duration.ofDays(40);

    private static final RedisScript<Long> TAKE = RedisScript.of(new ClassPathResource("lua/quota-take.lua"),
            Long.class);
    private static final RedisScript<Long> GIVE_BACK = RedisScript.of(new ClassPathResource("lua/quota-give-back.lua"),
            Long.class);

    private final ReactiveStringRedisTemplate redis;

    public OperationQuota(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String month(Instant at) {
        return MONTH.format(at);
    }

    static String key(UUID tenantId, String month) {
        return "cg:{" + tenantId + "}:ops:" + month;
    }

    /** True when the operation fits in the month and took a place; false when the month is used up. */
    public Mono<Boolean> take(UUID tenantId, String month, long limit) {
        return redis.execute(TAKE, List.of(key(tenantId, month)),
                        List.of(Long.toString(limit), Long.toString(KEEP.toSeconds())))
                .next().map(n -> n == 1L);
    }

    public Mono<Void> giveBack(UUID tenantId, String month) {
        return redis.execute(GIVE_BACK, List.of(key(tenantId, month)), List.of()).then();
    }
}
