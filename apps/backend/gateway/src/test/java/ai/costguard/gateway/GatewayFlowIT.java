package ai.costguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.AgentRiskSignal;
import ai.costguard.events.AgentState;
import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.ApprovalOutcome;
import ai.costguard.events.BudgetLevel;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.PolicyEffect;
import ai.costguard.events.PolicyRule;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.events.PriceUnit;
import ai.costguard.events.RiskAction;
import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.support.FakeProvider;
import ai.costguard.gateway.support.FakeSchemaRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ai.costguard.gateway.support.KafkaNode;

/**
 * The whole gateway on real Redis and Kafka: state arrives through the topics the core publishes, and decisions
 * leave through agent.operations.v1. Only the Schema Registry and the provider are stand-ins.
 */
@Testcontainers
@SpringBootTest
class GatewayFlowIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    static final KafkaNode KAFKA = new KafkaNode();

    static final FakeSchemaRegistry REGISTRY = start(FakeSchemaRegistry::new);
    static final FakeProvider OPENAI = start(FakeProvider::new);

    interface Starter<T> {
        T start() throws Exception;
    }

    static <T> T start(Starter<T> s) {
        try {
            return s.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        REGISTRY.close();
        OPENAI.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("costguard.gateway.schema-registry-url", () -> REGISTRY.url().toString());
        registry.add("costguard.gateway.providers.openai.url", () -> OPENAI.url().toString());
        registry.add("costguard.gateway.providers.openai.api-key", () -> "sk-provider-secret");
        registry.add("costguard.gateway.egress.spark.url", () -> OPENAI.url().toString());
        registry.add("costguard.gateway.egress.spark.vendor", () -> "SPARK");
        registry.add("costguard.gateway.egress.spark.header", () -> "Authorization");
        registry.add("costguard.gateway.egress.spark.value", () -> "Bearer spark-secret");
        registry.add("costguard.gateway.egress.spark.price", () -> "15");
        registry.add("costguard.gateway.buffer-file", () -> System.getProperty("java.io.tmpdir")
                + "/costguard-gateway-it/" + UUID.randomUUID() + ".buf");
    }

    @Autowired ApplicationContext context;
    WebTestClient web;
    @Autowired BudgetReserve reserve;

    private final SchemaRegistryClient registry = new SchemaRegistryClient(REGISTRY.url());
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID agentBudget = UUID.randomUUID();
    private final UUID departmentBudget = UUID.randomUUID();
    private final UUID companyBudget = UUID.randomUUID();
    private final String secret = "cg_live_" + UUID.randomUUID();

    private static KafkaProducer<String, byte[]> producer() {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        p.put("key.serializer", StringSerializer.class);
        p.put("value.serializer", ByteArraySerializer.class);
        return new KafkaProducer<>(p);
    }

    private void publish(String topic, String key, SpecificRecord record) {
        try (KafkaProducer<String, byte[]> producer = producer()) {
            producer.send(new ProducerRecord<>(topic, key, AvroWire.encode(registry.idOf(topic, record.getSchema()),
                    record))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(100);
        }
    }

    @Autowired AgentDirectory directory;

    private void agentState(boolean suspended, String expression, String threshold) throws InterruptedException {
        publish("agents.state.v1", agent.toString(), AgentState.newBuilder().setTenantId(tenant).setAgentId(agent)
                .setVirtualKeyHashes(List.of(AgentDirectory.hash(secret))).setSuspended(suspended).setMandateVersion(1)
                .setMandateExpression(expression).setApprovalThreshold(money(threshold))
                .setUpdatedAt(Instant.now()).build());
        eventually(() -> directory.byKey(secret).map(s -> s.getSuspended() == suspended
                && expression.equals(s.getMandateExpression())).orElse(false));
    }

    private void limit(UUID budget, UUID parent, BudgetLevel level, UUID ref, String amount) {
        publish("budgets.limits.v1", budget.toString(), BudgetLimit.newBuilder().setTenantId(tenant).setBudgetId(budget)
                .setParentBudgetId(parent).setLevel(level).setRefId(ref).setLimitAmount(money(amount))
                .setCurrency("RUB").setCorrection(money("0")).setPeriodStart(LocalDate.of(2026, 11, 1))
                .setPeriodEnd(LocalDate.of(2026, 11, 30)).setVersion(1).build());
    }

    private void budgets(String agentLimit) throws InterruptedException {
        limit(companyBudget, null, BudgetLevel.COMPANY, null, "100000");
        limit(departmentBudget, companyBudget, BudgetLevel.DEPARTMENT, UUID.randomUUID(), "50000");
        limit(agentBudget, departmentBudget, BudgetLevel.AGENT, agent, agentLimit);
        eventually(() -> {
            List<BigDecimal> c = reserve.counters(tenant, agentBudget).block();
            return c != null && c.get(0).compareTo(new BigDecimal(agentLimit)) == 0;
        });
    }

    private static final String ALLOW_ALL = "cost <= 100000.0";

    @BeforeEach
    void reset() {
        web = WebTestClient.bindToApplicationContext(context).configureClient()
                .responseTimeout(Duration.ofSeconds(30)).build();
        OPENAI.received.clear();
        OPENAI.status = 200;
    }

    private WebTestClient.ResponseSpec authorize(UUID op, String amount) {
        return web.post().uri("/v1/spend/authorize").header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", op.toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vendor", "SPARK", "kind", "paid_api", "amount", new BigDecimal(amount)))
                .exchange();
    }

    private List<AgentOperation> operations() {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        p.put("group.id", "test-" + UUID.randomUUID());
        p.put("auto.offset.reset", "earliest");
        p.put("key.deserializer", StringDeserializer.class);
        p.put("value.deserializer", ByteArrayDeserializer.class);
        List<AgentOperation> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of("agent.operations.v1"));
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> out.add(AvroWire.decode(r.value(), registry,
                        AgentOperation.class)));
            }
        }
        return out.stream().filter(o -> o.getAgentId().equals(agent)).toList();
    }

    /** schemas/openapi/gateway.yaml is exported from the code; -Popenapi.update=true rewrites it. */
    @Test
    void committedSpecificationMatchesTheCode() throws Exception {
        String actual = web.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        java.nio.file.Path committed = java.nio.file.Path.of(System.getProperty("repo.root"), "schemas", "openapi",
                "gateway.yaml");
        if (Boolean.getBoolean("openapi.update")) {
            java.nio.file.Files.writeString(committed, actual);
        }
        assertThat(java.nio.file.Files.readString(committed)).isEqualToNormalizingNewlines(actual);
    }

    @Test
    void anUnknownKeyIsRefused() {
        web.post().uri("/v1/spend/authorize").header("Authorization", "Bearer cg_live_nobody")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vendor", "SPARK", "amount", 1)).exchange()
                .expectStatus().isEqualTo(401).expectBody().jsonPath("$.error.type").isEqualTo("invalid_api_key");
    }

    @Test
    void spendIsReservedSettledAndRecorded() throws Exception {
        agentState(false, ALLOW_ALL, "10000");
        budgets("1000");
        UUID op = UUID.randomUUID();

        authorize(op, "300").expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("authorized");
        authorize(op, "300").expectStatus().isEqualTo(409).expectBody().jsonPath("$.error.type")
                .isEqualTo("duplicate_operation");
        web.post().uri("/v1/spend/settle").header("Authorization", "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("authorizationId", op.toString(), "amount", new BigDecimal("250"))).exchange()
                .expectStatus().isOk();

        List<BigDecimal> counters = reserve.counters(tenant, companyBudget).block();
        assertThat(counters.get(1)).isEqualByComparingTo("250");
        assertThat(counters.get(2)).isEqualByComparingTo("0");
        authorize(op, "300").expectStatus().isEqualTo(409);

        eventually(() -> operations().stream().anyMatch(o -> o.getOperationId().equals(op)
                && o.getDecision() == OperationDecision.ALLOWED && o.getActualCost().compareTo(money("250")) == 0));
    }

    @Test
    void theLadderRefusesInItsOrder() throws Exception {
        agentState(false, "vendor == \"SPARK\" && cost <= 500.0", "10000");
        budgets("100");

        authorize(UUID.randomUUID(), "600").expectStatus().isEqualTo(403).expectBody().jsonPath("$.error.type")
                .isEqualTo("policy_denied");
        authorize(UUID.randomUUID(), "150").expectStatus().isEqualTo(402).expectBody().jsonPath("$.error.type")
                .isEqualTo("budget_exceeded");
        assertThat(reserve.counters(tenant, agentBudget).block().get(2)).isEqualByComparingTo("0");

        publish("policy.snapshots.v1", tenant.toString(), PolicySnapshot.newBuilder().setTenantId(tenant).setVersion(1)
                .setRules(List.of(PolicyRule.newBuilder().setId(UUID.randomUUID()).setName("no small")
                        .setExpression("cost < 10.0").setEffect(PolicyEffect.DENY).build()))
                .setPublishedAt(Instant.now()).build());
        eventually(() -> authorize(UUID.randomUUID(), "5").returnResult(String.class).getStatus().value() == 403);

        publish("agent.risk-signals.v1", agent.toString(), AgentRiskSignal.newBuilder().setTenantId(tenant)
                .setAgentId(agent).setTaskId(null).setScore(80).setAction(RiskAction.SLOW_DOWN)
                .setReasons(List.of("velocity")).setDetectedAt(Instant.now()).build());
        eventually(() -> authorize(UUID.randomUUID(), "50").returnResult(String.class).getStatus().value() == 429);
    }

    @Test
    void aSuspendedAgentGets423() throws Exception {
        agentState(true, ALLOW_ALL, "10000");
        budgets("1000");

        authorize(UUID.randomUUID(), "1").expectStatus().isEqualTo(423).expectBody().jsonPath("$.error.type")
                .isEqualTo("agent_suspended");
    }

    @Test
    void aLargeOperationWaitsForAHumanAndPassesOnRetry() throws Exception {
        agentState(false, ALLOW_ALL, "100");
        budgets("1000");
        UUID op = UUID.randomUUID();

        authorize(op, "300").expectStatus().isEqualTo(403).expectBody()
                .jsonPath("$.error.type").isEqualTo("approval_required")
                .jsonPath("$.error.approvalId").isEqualTo(op.toString());
        assertThat(reserve.counters(tenant, agentBudget).block().get(2)).isEqualByComparingTo("0");
        web.get().uri("/v1/spend/approvals/" + op).header("Authorization", "Bearer " + secret).exchange()
                .expectBody().jsonPath("$.status").isEqualTo("pending");

        publish("approvals.decisions.v1", op.toString(), ApprovalDecision.newBuilder().setTenantId(tenant)
                .setApprovalId(UUID.randomUUID()).setOperationId(op).setAgentId(agent)
                .setStatus(ApprovalOutcome.APPROVED).setAmount(money("300")).setDecidedAt(Instant.now()).build());
        eventually(() -> authorize(op, "300").returnResult(String.class).getStatus().value() == 200);

        web.get().uri("/v1/spend/approvals/" + op).header("Authorization", "Bearer " + secret).exchange()
                .expectBody().jsonPath("$.status").isEqualTo("approved");
        authorize(UUID.randomUUID(), "300").expectStatus().isEqualTo(403);
        eventually(() -> operations().stream().anyMatch(o -> o.getOperationId().equals(op)
                && o.getDecision() == OperationDecision.PENDING_APPROVAL));
    }

    private void prices(String in, String out) {
        for (var p : List.of(Map.entry(PriceUnit.INPUT_TOKEN, in), Map.entry(PriceUnit.OUTPUT_TOKEN, out))) {
            publish("catalog.prices.v1", tenant + ":gpt-5:" + p.getKey(), CatalogPrice.newBuilder().setTenantId(tenant)
                    .setSkuId("gpt-5:" + p.getKey()).setProvider("OpenAI").setModel("gpt-5").setUnit(p.getKey())
                    .setUnitPrice(money(p.getValue())).setCurrency("RUB").setValidFrom(LocalDate.of(2026, 1, 1)).build());
        }
    }

    private WebTestClient.ResponseSpec chat(UUID op, String body) {
        return web.post().uri("/v1/providers/openai/v1/chat/completions").header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", op.toString()).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    @Test
    void aModelCallIsProxiedWithTheProviderKeyAndSettledFromUsage() throws Exception {
        agentState(false, "model in [\"gpt-5\"] && cost <= 1000.0", "10000");
        budgets("1000");
        prices("0.01", "0.02");
        UUID op = UUID.randomUUID();
        String body = "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";

        eventually(() -> chat(UUID.randomUUID(), "{\"model\":\"gpt-5\",\"messages\":[]}")
                .returnResult(String.class).getStatus().value() == 200);
        OPENAI.received.clear();
        chat(op, body).expectStatus().isOk().expectBody().jsonPath("$.choices[0].message.content").isEqualTo("ok");

        FakeProvider.Received sent = OPENAI.received.getFirst();
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        assertThat(sent.headers().get("Authorization")).containsExactly("Bearer sk-provider-secret");
        assertThat(sent.headers().toString()).doesNotContain(secret);
        assertThat(sent.body()).contains("\"max_tokens\":1024");

        eventually(() -> operations().stream().anyMatch(o -> o.getOperationId().equals(op)
                && o.getActualCost() != null && o.getActualCost().compareTo(money("2")) == 0));
        assertThat(reserve.counters(tenant, agentBudget).block().get(2)).isEqualByComparingTo("0");
    }

    @Test
    void aCallThatDoesNotFitGetsFewerOutputTokens() throws Exception {
        agentState(false, "cost <= 100000.0", "100000");
        budgets("10");
        prices("0.01", "0.02");

        eventually(() -> chat(UUID.randomUUID(), "{\"model\":\"gpt-5\",\"messages\":[],\"max_tokens\":5}")
                .returnResult(String.class).getStatus().value() == 200);
        OPENAI.received.clear();
        chat(UUID.randomUUID(), "{\"model\":\"gpt-5\",\"messages\":[],\"max_tokens\":4000}").expectStatus().isOk();

        assertThat(OPENAI.received.getFirst().body()).doesNotContain("4000").contains("\"max_tokens\":");
    }

    @Test
    void aProviderErrorGivesTheReserveBack() throws Exception {
        agentState(false, ALLOW_ALL, "100000");
        budgets("1000");
        prices("0.01", "0.02");
        eventually(() -> chat(UUID.randomUUID(), "{\"model\":\"gpt-5\",\"messages\":[]}")
                .returnResult(String.class).getStatus().value() == 200);
        List<BigDecimal> before = reserve.counters(tenant, agentBudget).block();
        OPENAI.status = 500;

        chat(UUID.randomUUID(), "{\"model\":\"gpt-5\",\"messages\":[]}").expectStatus().isEqualTo(500);

        List<BigDecimal> after = reserve.counters(tenant, agentBudget).block();
        assertThat(after.get(1)).isEqualByComparingTo(before.get(1));
        assertThat(after.get(2)).isEqualByComparingTo("0");
    }

    @Test
    void aModelWithNoPriceIsRefused() throws Exception {
        agentState(false, ALLOW_ALL, "100000");
        budgets("1000");

        chat(UUID.randomUUID(), "{\"model\":\"unpriced\",\"messages\":[]}").expectStatus().isEqualTo(403)
                .expectBody().jsonPath("$.error.type").isEqualTo("price_unknown");
        assertThat(OPENAI.received).isEmpty();
    }

    private WebTestClient.ResponseSpec paidApi(UUID op) {
        return web.get().uri("/v1/egress/spark/v1/companies/7707083893").header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", op.toString()).exchange();
    }

    @Test
    void aPaidApiCallIsReservedProxiedAndCharged() throws Exception {
        agentState(false, ALLOW_ALL, "100000");
        budgets("1000");
        eventually(() -> paidApi(UUID.randomUUID()).returnResult(String.class).getStatus().value() == 200);
        List<BigDecimal> before = reserve.counters(tenant, agentBudget).block();
        OPENAI.received.clear();
        UUID op = UUID.randomUUID();

        paidApi(op).expectStatus().isOk();

        FakeProvider.Received sent = OPENAI.received.getFirst();
        assertThat(sent.path()).isEqualTo("/v1/companies/7707083893");
        assertThat(sent.headers().get("Authorization")).containsExactly("Bearer spark-secret");
        assertThat(sent.headers().toString()).doesNotContain(secret);
        List<BigDecimal> after = reserve.counters(tenant, agentBudget).block();
        assertThat(after.get(1).subtract(before.get(1))).isEqualByComparingTo("15");
        assertThat(after.get(2)).isEqualByComparingTo("0");
        eventually(() -> operations().stream().anyMatch(o -> o.getOperationId().equals(op)
                && o.getDecision() == OperationDecision.ALLOWED && o.getActualCost().compareTo(money("15")) == 0));
    }

    @Test
    void aPaidApiErrorGivesTheReserveBackAndIsRecorded() throws Exception {
        agentState(false, ALLOW_ALL, "100000");
        budgets("1000");
        eventually(() -> paidApi(UUID.randomUUID()).returnResult(String.class).getStatus().value() == 200);
        List<BigDecimal> before = reserve.counters(tenant, agentBudget).block();
        OPENAI.status = 502;
        UUID op = UUID.randomUUID();

        paidApi(op).expectStatus().isEqualTo(502);

        List<BigDecimal> after = reserve.counters(tenant, agentBudget).block();
        assertThat(after.get(1)).isEqualByComparingTo(before.get(1));
        assertThat(after.get(2)).isEqualByComparingTo("0");
        eventually(() -> operations().stream().anyMatch(o -> o.getOperationId().equals(op)
                && o.getDecision() == OperationDecision.DENIED && "upstream_error".equals(o.getDenialCode())));
    }
}
