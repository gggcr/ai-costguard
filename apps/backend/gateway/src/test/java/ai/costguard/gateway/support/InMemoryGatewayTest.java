package ai.costguard.gateway.support;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.AgentState;
import ai.costguard.events.BudgetLevel;
import ai.costguard.events.BudgetLimit;
import ai.costguard.gateway.answers.Answer;
import ai.costguard.gateway.answers.AnswerCache;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.budget.BudgetTree;
import ai.costguard.gateway.events.OperationLog;
import ai.costguard.gateway.filters.LoopFilter;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.plans.OperationQuota;
import ai.costguard.gateway.plans.PlanBoard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * The whole gateway without Docker: Redis is replaced by an in-memory reserve with the semantics of the Lua
 * scripts, Kafka by a recording operation log, and the state the topics would bring is applied directly. The
 * Testcontainers tests (GatewayFlowIT) run the same paths on the real Redis and Kafka.
 */
@SpringBootTest(properties = {"costguard.gateway.sweep-delay=PT1H", "costguard.gateway.replay-delay=PT1H"})
@Import(InMemoryGatewayTest.Fakes.class)
public abstract class InMemoryGatewayTest {

    public static final MemoryReserve RESERVE = new MemoryReserve();
    public static final MemoryAnswers ANSWERS = new MemoryAnswers();

    /** The answers Redis would keep for retries, with a size limit a test may lower. */
    public static class MemoryAnswers extends AnswerCache {
        public final Map<String, Answer> kept = new ConcurrentHashMap<>();
        public volatile int limit = 1024 * 1024;

        MemoryAnswers() {
            super(null, Duration.ofHours(24), 1024 * 1024);
        }

        @Override
        public boolean fits(int bytes) {
            return bytes <= limit;
        }

        @Override
        public Mono<Boolean> keep(UUID tenantId, UUID operationId, Answer answer) {
            if (!fits(answer.body().length)) {
                return Mono.just(false);
            }
            kept.put(key(tenantId, operationId), answer);
            return Mono.just(true);
        }

        @Override
        public Mono<Answer> find(UUID tenantId, UUID operationId) {
            return Mono.justOrEmpty(kept.get(key(tenantId, operationId)));
        }
    }
    public static final MemoryQuota QUOTA = new MemoryQuota();

    /** What quota-take.lua and quota-give-back.lua do, per company and month. */
    public static class MemoryQuota extends OperationQuota {
        public final Map<String, Long> used = new ConcurrentHashMap<>();

        MemoryQuota() {
            super(null);
        }

        public long used(UUID tenantId, String month) {
            return used.getOrDefault(tenantId + ":" + month, 0L);
        }

        @Override
        public synchronized Mono<Boolean> take(UUID tenantId, String month, long limit) {
            String key = tenantId + ":" + month;
            if (used.getOrDefault(key, 0L) >= limit) {
                return Mono.just(false);
            }
            used.merge(key, 1L, Long::sum);
            return Mono.just(true);
        }

        @Override
        public synchronized Mono<Void> giveBack(UUID tenantId, String month) {
            used.computeIfPresent(tenantId + ":" + month, (k, n) -> n > 0 ? n - 1 : 0);
            return Mono.empty();
        }
    }

    /** All levels or none, settle and release once: what reserve.lua, settle.lua and release.lua do. */
    public static class MemoryReserve extends BudgetReserve {
        public final Map<String, BigDecimal> reserved = new ConcurrentHashMap<>();
        public final Map<String, BigDecimal> settled = new ConcurrentHashMap<>();
        public final List<String> released = new CopyOnWriteArrayList<>();
        public volatile BigDecimal left = new BigDecimal("1000");

        MemoryReserve() {
            super(null, Clock.systemUTC(), Duration.ofMinutes(15));
        }

        @Override
        public Mono<Outcome> reserve(UUID tenantId, String operationId, List<UUID> chain, BigDecimal amount) {
            if (reserved.containsKey(operationId) || settled.containsKey(operationId)) {
                return Mono.just(new Outcome.Duplicate());
            }
            if (amount.compareTo(left) > 0) {
                return Mono.just(new Outcome.Exceeded(chain.getLast(), left));
            }
            reserved.put(operationId, amount);
            return Mono.just(new Outcome.Reserved());
        }

        @Override
        public Mono<Boolean> settle(UUID tenantId, String operationId, BigDecimal actual) {
            if (reserved.remove(operationId) == null) {
                return Mono.just(false);
            }
            settled.put(operationId, actual);
            return Mono.just(true);
        }

        @Override
        public Mono<Boolean> release(UUID tenantId, String operationId) {
            boolean had = reserved.remove(operationId) != null;
            if (had) {
                released.add(operationId);
            }
            return Mono.just(had);
        }

        @Override
        public Mono<Long> sweep() {
            return Mono.just(0L);
        }
    }

    public static class RecordingLog extends OperationLog {
        public final List<AgentOperation> recorded = new CopyOnWriteArrayList<>();

        RecordingLog() {
            super(null, null, null);
        }

        @Override
        public void record(AgentOperation op) {
            recorded.add(op);
        }

        @Override
        public int replay() {
            return 0;
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MemoryReserve memoryReserve() {
            return RESERVE;
        }

        @Bean
        @Primary
        MemoryAnswers memoryAnswers() {
            return ANSWERS;
        }

        @Bean
        @Primary
        RecordingLog recordingLog() {
            return new RecordingLog();
        }

        @Bean
        @Primary
        MemoryQuota memoryQuota() {
            return QUOTA;
        }

        /** No Kafka here: listeners would only retry the connection. */
        @Bean
        static BeanPostProcessor noKafkaListeners() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                        factory.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }

    /** Step 3 asks Redis whether the operation id was seen; here the in-memory reserve answers. */
    @TestBean(name = "loopFilter", methodName = "ai.costguard.gateway.support.InMemoryGatewayTest#memoryLoopFilter")
    LoopFilter loopFilter;

    static LoopFilter memoryLoopFilter() {
        return new LoopFilter(null, null) {
            @Override
            public Mono<StepResult> check(Operation op) {
                String id = op.operationId().toString();
                if (!RESERVE.reserved.containsKey(id) && !RESERVE.settled.containsKey(id)) {
                    return Mono.just(new StepResult.Pass());
                }
                return ANSWERS.find(op.tenantId(), op.operationId())
                        .map(answer -> (StepResult) new StepResult.Answered(answer))
                        .defaultIfEmpty(new StepResult.Refuse(409, "duplicate_operation"));
            }
        };
    }

    @Autowired protected ApplicationContext context;
    @Autowired protected AgentDirectory directory;
    @Autowired protected BudgetTree tree;
    @Autowired protected MemoryReserve reserve;
    @Autowired protected RecordingLog log;
    @Autowired protected PlanBoard plans;

    protected final UUID tenant = UUID.randomUUID();
    protected final UUID agent = UUID.randomUUID();
    protected final String secret = "cg_live_" + UUID.randomUUID();
    protected WebTestClient web;

    protected static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN);
    }

    /** An agent with a mandate up to 1000, an approval threshold and a budget of 1000 under a company budget. */
    protected void agentWithBudget(String approvalThreshold) {
        web = WebTestClient.bindToApplicationContext(context).configureClient()
                .responseTimeout(Duration.ofSeconds(30)).build();
        reserve.left = new BigDecimal("1000");
        ANSWERS.limit = 1024 * 1024;
        directory.apply(AgentState.newBuilder().setTenantId(tenant).setAgentId(agent)
                .setVirtualKeyHashes(List.of(AgentDirectory.hash(secret))).setSuspended(false).setMandateVersion(3)
                .setMandateExpression("cost <= 1000.0").setApprovalThreshold(money(approvalThreshold))
                .setUpdatedAt(Instant.now()).build());
        UUID company = UUID.randomUUID();
        tree.apply(BudgetLimit.newBuilder().setTenantId(tenant).setBudgetId(company).setParentBudgetId(null)
                .setLevel(BudgetLevel.COMPANY).setRefId(null).setLimitAmount(money("100000")).setCurrency("RUB")
                .setCorrection(money("0")).setPeriodStart(LocalDate.of(2026, 1, 1))
                .setPeriodEnd(LocalDate.of(2026, 12, 31)).setVersion(1).build());
        tree.apply(BudgetLimit.newBuilder().setTenantId(tenant).setBudgetId(UUID.randomUUID())
                .setParentBudgetId(company).setLevel(BudgetLevel.AGENT).setRefId(agent).setLimitAmount(money("1000"))
                .setCurrency("RUB").setCorrection(money("0")).setPeriodStart(LocalDate.of(2026, 1, 1))
                .setPeriodEnd(LocalDate.of(2026, 12, 31)).setVersion(1).build());
    }

    protected List<AgentOperation> recorded(UUID operation) {
        return log.recorded.stream().filter(o -> o.getOperationId().equals(operation)).toList();
    }

    protected static FakeProvider fakeApi() {
        try {
            return new FakeProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
