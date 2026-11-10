package ai.costguard.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

/** The Lua scripts on a real Redis: the money rules are only as good as these scripts. */
@Testcontainers
class BudgetReserveIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connections;
    static ReactiveStringRedisTemplate redis;

    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-11-07T10:00:00Z"));
    static final Clock CLOCK = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return NOW.get();
        }
    };

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new ReactiveStringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID department = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final BudgetReserve reserve = new BudgetReserve(redis, CLOCK, Duration.ofMinutes(15));

    private List<UUID> chain() {
        return List.of(agent, department, company);
    }

    private void limits(String agentLimit, String departmentLimit, String companyLimit) {
        reserve.applyLimit(tenant, agent, new BigDecimal(agentLimit), 1, BigDecimal.ZERO).block();
        reserve.applyLimit(tenant, department, new BigDecimal(departmentLimit), 1, BigDecimal.ZERO).block();
        reserve.applyLimit(tenant, company, new BigDecimal(companyLimit), 1, BigDecimal.ZERO).block();
    }

    private List<BigDecimal> counters(UUID budget) {
        return reserve.counters(tenant, budget).block();
    }

    @Test
    void aReserveTakesEveryLevelOrNone() {
        limits("100", "30", "1000");

        BudgetReserve.Outcome blocked = reserve.reserve(tenant, "op-1", chain(), new BigDecimal("50")).block();

        assertThat(blocked).isInstanceOf(BudgetReserve.Outcome.Exceeded.class);
        assertThat(((BudgetReserve.Outcome.Exceeded) blocked).budgetId()).isEqualTo(department);
        assertThat(((BudgetReserve.Outcome.Exceeded) blocked).left()).isEqualByComparingTo("30");
        assertThat(counters(agent).get(2)).isEqualByComparingTo("0");

        assertThat(reserve.reserve(tenant, "op-2", chain(), new BigDecimal("20.5")).block())
                .isInstanceOf(BudgetReserve.Outcome.Reserved.class);
        for (UUID b : chain()) {
            assertThat(counters(b).get(2)).isEqualByComparingTo("20.5");
        }
    }

    @Test
    void parallelReservesNeverOverspend() {
        limits("500", "10000", "10000");

        long reserved = Flux.range(0, 100)
                .flatMap(i -> reserve.reserve(tenant, "op-" + i, chain(), BigDecimal.TEN), 32)
                .filter(o -> o instanceof BudgetReserve.Outcome.Reserved).count().block();

        assertThat(reserved).isEqualTo(50);
        assertThat(counters(agent).get(2)).isEqualByComparingTo("500");
        assertThat(counters(company).get(2)).isEqualByComparingTo("500");
    }

    @Test
    void settleChargesTheActualAndReturnsTheRestOnce() {
        limits("100", "100", "100");
        reserve.reserve(tenant, "op", chain(), new BigDecimal("40")).block();

        assertThat(reserve.settle(tenant, "op", new BigDecimal("12.345678")).block()).isTrue();
        assertThat(reserve.settle(tenant, "op", new BigDecimal("12.345678")).block()).isTrue();

        for (UUID b : chain()) {
            assertThat(counters(b).get(1)).isEqualByComparingTo("12.345678");
            assertThat(counters(b).get(2)).isEqualByComparingTo("0");
        }
        assertThat(reserve.settle(tenant, "never-reserved", BigDecimal.ONE).block()).isFalse();
    }

    @Test
    void aRepeatedOperationIsNotReservedTwice() {
        limits("100", "100", "100");

        reserve.reserve(tenant, "op", chain(), BigDecimal.TEN).block();

        assertThat(reserve.reserve(tenant, "op", chain(), BigDecimal.TEN).block())
                .isInstanceOf(BudgetReserve.Outcome.Duplicate.class);
        assertThat(counters(agent).get(2)).isEqualByComparingTo("10");
    }

    @Test
    void releaseGivesEverythingBack() {
        limits("100", "100", "100");
        reserve.reserve(tenant, "op", chain(), BigDecimal.TEN).block();

        assertThat(reserve.release(tenant, "op").block()).isTrue();
        assertThat(reserve.release(tenant, "op").block()).isFalse();
        assertThat(counters(company).get(2)).isEqualByComparingTo("0");
    }

    @Test
    void theSweeperReleasesReservesPastTheirHold() {
        limits("100", "100", "100");
        reserve.reserve(tenant, "abandoned", chain(), BigDecimal.TEN).block();

        reserve.sweep().block();
        assertThat(counters(agent).get(2)).isEqualByComparingTo("10");

        NOW.updateAndGet(t -> t.plus(Duration.ofMinutes(16)));
        reserve.sweep().block();
        assertThat(counters(agent).get(2)).isEqualByComparingTo("0");
    }

    @Test
    void anOlderLimitNeverReplacesANewerOne() {
        reserve.applyLimit(tenant, agent, new BigDecimal("100"), 5, BigDecimal.ZERO).block();

        assertThat(reserve.applyLimit(tenant, agent, new BigDecimal("999"), 4, BigDecimal.ZERO).block()).isFalse();
        assertThat(reserve.applyLimit(tenant, agent, new BigDecimal("200"), 6, new BigDecimal("-3")).block()).isTrue();

        assertThat(counters(agent).get(0)).isEqualByComparingTo("200");
        assertThat(counters(agent).get(1)).isEqualByComparingTo("-3");
    }

    /** ADR 0040: the ledger's snapshot plus what the gateway settled after its moment. */
    @Test
    void aSnapshotOfTheLedgerBringsBackACounterLostWithRedis() {
        limits("1000", "1000", "1000");
        Instant start = NOW.get();
        reserve.reserve(tenant, "before", chain(), new BigDecimal("100")).block();
        reserve.settle(tenant, "before", new BigDecimal("100")).block();
        Instant asOf = start.plusSeconds(60);
        NOW.set(asOf.plusSeconds(30));
        reserve.reserve(tenant, "after", chain(), new BigDecimal("30")).block();
        reserve.settle(tenant, "after", new BigDecimal("30")).block();

        // Redis lost the agent's counter; the limit comes back from the compacted topic, spent from zero
        redis.delete(BudgetReserve.budgetKey(tenant, agent)).block();
        reserve.applyLimit(tenant, agent, new BigDecimal("1000"), 1, BigDecimal.ZERO).block();
        assertThat(counters(agent).get(1)).isEqualByComparingTo("0");

        // the ledger counted the charge before the moment; the log holds the one after it
        reserve.applyLimit(tenant, agent, new BigDecimal("1000"), 1, BigDecimal.ZERO, new BigDecimal("100"), asOf)
                .block();
        assertThat(counters(agent).get(1)).isEqualByComparingTo("130");
        // a counter that was right stays right
        reserve.applyLimit(tenant, company, new BigDecimal("1000"), 1, BigDecimal.ZERO, new BigDecimal("100"), asOf)
                .block();
        assertThat(counters(company).get(1)).isEqualByComparingTo("130");
        // the same snapshot again changes nothing
        reserve.applyLimit(tenant, agent, new BigDecimal("1000"), 1, BigDecimal.ZERO, new BigDecimal("100"), asOf)
                .block();
        assertThat(counters(agent).get(1)).isEqualByComparingTo("130");
        NOW.set(start);
    }

    @Test
    void aBudgetWithNoLimitHoldsNothing() {
        assertThat(reserve.reserve(tenant, "op", List.of(UUID.randomUUID()), BigDecimal.ONE).block())
                .isInstanceOf(BudgetReserve.Outcome.Exceeded.class);
        assertThat(IntStream.of(0)).isNotEmpty();
    }
}
