package ai.costguard.gateway.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Budget counters in Redis. Only the gateway writes them. Every key of a tenant carries the {tenant} hash tag, so
 * one script touches one cluster slot.
 */
@Component
public class BudgetReserve {

    public sealed interface Outcome {
        record Reserved() implements Outcome {}
        record Duplicate() implements Outcome {}
        record Exceeded(UUID budgetId, BigDecimal left) implements Outcome {}
    }

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("lua/reserve.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SETTLE = RedisScript.of(new ClassPathResource("lua/settle.lua"), List.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("lua/release.lua"), Long.class);
    private static final RedisScript<Long> LIMIT = RedisScript.of(new ClassPathResource("lua/limit.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final Duration hold;

    public BudgetReserve(ReactiveStringRedisTemplate redis, Clock clock,
            @Value("${costguard.gateway.reservation-hold:PT15M}") Duration hold) {
        this.redis = redis;
        this.clock = clock;
        this.hold = hold;
    }

    static String budgetKey(UUID tenantId, UUID budgetId) {
        return "cg:{" + tenantId + "}:b:" + budgetId;
    }

    static String reservationKey(UUID tenantId, String operationId) {
        return "cg:{" + tenantId + "}:r:" + operationId;
    }

    static String openKey(UUID tenantId) {
        return "cg:{" + tenantId + "}:open";
    }

    static String settledKey(UUID tenantId, String operationId) {
        return "cg:{" + tenantId + "}:s:" + operationId;
    }

    static final String TENANTS = "cg:tenants";

    /** All budgets of the chain, or none. */
    @SuppressWarnings("unchecked")
    public Mono<Outcome> reserve(UUID tenantId, String operationId, List<UUID> chain, BigDecimal amount) {
        List<String> keys = new ArrayList<>();
        keys.add(reservationKey(tenantId, operationId));
        keys.add(openKey(tenantId));
        chain.forEach(b -> keys.add(budgetKey(tenantId, b)));
        long expiresAt = clock.instant().plus(hold).getEpochSecond();
        return redis.opsForSet().add(TENANTS, tenantId.toString())
                .then(redis.execute(RESERVE, keys, List.of(Long.toString(Money.micros(amount)),
                        Long.toString(expiresAt), Long.toString(hold.multipliedBy(2).toSeconds()))).next())
                .map(result -> {
                    List<Object> r = (List<Object>) result;
                    if (((Long) r.get(0)) == 1L) {
                        return "exists".equals(r.get(1)) ? new Outcome.Duplicate() : new Outcome.Reserved();
                    }
                    String key = (String) r.get(1);
                    UUID budget = UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
                    String left = (String) r.get(2);
                    return new Outcome.Exceeded(budget, "no_limit".equals(left) ? BigDecimal.ZERO
                            : Money.of(Long.parseLong(left)));
                });
    }

    /** Returns false if there was no such reservation: it expired, or the operation was never reserved. */
    @SuppressWarnings("unchecked")
    public Mono<Boolean> settle(UUID tenantId, String operationId, BigDecimal actual) {
        return redis.execute(SETTLE,
                        List.of(reservationKey(tenantId, operationId), openKey(tenantId), settledKey(tenantId, operationId)),
                        List.of(Long.toString(Money.micros(actual)), Long.toString(Duration.ofDays(7).toSeconds())))
                .next().map(r -> ((Long) ((List<Object>) r).get(0)) == 1L);
    }

    public Mono<Boolean> release(UUID tenantId, String operationId) {
        return redis.execute(RELEASE, List.of(reservationKey(tenantId, operationId), openKey(tenantId)), List.of())
                .next().map(n -> n == 1L);
    }

    public Mono<Boolean> applyLimit(UUID tenantId, UUID budgetId, BigDecimal limit, long version, BigDecimal correction) {
        return redis.execute(LIMIT, List.of(budgetKey(tenantId, budgetId)),
                        List.of(Long.toString(Money.micros(limit)), Long.toString(version),
                                Long.toString(correction.signum() < 0 ? -Money.micros(correction.negate())
                                        : Money.micros(correction))))
                .next().map(n -> n == 1L);
    }

    /** Releases reservations past their hold: the agent never settled, so the money goes back. */
    public Mono<Long> sweep() {
        long now = clock.instant().getEpochSecond();
        return redis.opsForSet().members(TENANTS)
                .flatMap(tenant -> redis.opsForZSet()
                        .rangeByScore(openKey(UUID.fromString(tenant)),
                                org.springframework.data.domain.Range.closed(0.0, (double) now))
                        .flatMap(key -> redis.execute(RELEASE, List.of(key, openKey(UUID.fromString(tenant))), List.of())
                                .next()))
                .reduce(0L, Long::sum);
    }

    /** Counters of one budget, for tests and diagnostics: limit, spent, reserved. */
    public Mono<List<BigDecimal>> counters(UUID tenantId, UUID budgetId) {
        return redis.opsForHash().multiGet(budgetKey(tenantId, budgetId), List.of("limit", "spent", "reserved"))
                .map(values -> values.stream()
                        .map(v -> v == null ? BigDecimal.ZERO : Money.of(Long.parseLong(v.toString()))).toList());
    }
}
