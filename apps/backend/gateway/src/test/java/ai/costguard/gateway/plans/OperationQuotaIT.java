package ai.costguard.gateway.plans;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

/** quota-take.lua and quota-give-back.lua on a real Redis (ADR 0037): the plan's limit holds under concurrency. */
@Testcontainers
class OperationQuotaIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connections;
    static ReactiveStringRedisTemplate redis;
    static OperationQuota quota;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new ReactiveStringRedisTemplate(connections);
        quota = new OperationQuota(redis);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private static long counter(UUID tenant, String month) {
        String value = redis.opsForValue().get(OperationQuota.key(tenant, month)).block();
        return value == null ? 0 : Long.parseLong(value);
    }

    @Test
    void manyOperationsAtOnceTakeNoMoreThanTheLimit() {
        UUID tenant = UUID.randomUUID();

        long took = Flux.fromStream(IntStream.range(0, 50).boxed())
                .flatMap(i -> quota.take(tenant, "2026-11", 7), 50)
                .filter(Boolean::booleanValue).count().block();

        assertThat(took).isEqualTo(7);
        assertThat(counter(tenant, "2026-11")).isEqualTo(7);
        Long ttl = redis.getExpire(OperationQuota.key(tenant, "2026-11")).map(d -> d.toSeconds()).block();
        assertThat(ttl).isPositive().isLessThanOrEqualTo(OperationQuota.KEEP.toSeconds());
    }

    @Test
    void aPlaceGivenBackCanBeTakenAgainAndTheCounterNeverGoesNegative() {
        UUID tenant = UUID.randomUUID();
        assertThat(quota.take(tenant, "2026-11", 1).block()).isTrue();
        assertThat(quota.take(tenant, "2026-11", 1).block()).isFalse();

        quota.giveBack(tenant, "2026-11").block();
        assertThat(quota.take(tenant, "2026-11", 1).block()).isTrue();

        quota.giveBack(tenant, "2026-11").block();
        quota.giveBack(tenant, "2026-11").block();
        assertThat(counter(tenant, "2026-11")).isZero();
    }

    @Test
    void monthsAreCountedApart() {
        UUID tenant = UUID.randomUUID();
        assertThat(quota.take(tenant, "2026-11", 1).block()).isTrue();

        assertThat(quota.take(tenant, "2026-12", 1).block()).isTrue();
    }
}
