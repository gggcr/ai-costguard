package ai.costguard.gateway.egress;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.CatalogPrice;
import ai.costguard.events.ExchangeRate;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.OperationKind;
import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.approvals.HumanOnlyAssessor;
import ai.costguard.gateway.approvals.TaskFitAssessor;
import ai.costguard.gateway.rules.PriceBook;
import ai.costguard.gateway.rules.RateBook;
import ai.costguard.gateway.support.FakeProvider;
import ai.costguard.gateway.support.InMemoryGatewayTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** A paid API through the whole gateway: the same ladder, reserve and settlement as a model call (ADR 0010). */
class EgressProxyTest extends InMemoryGatewayTest {

    static final FakeProvider API = fakeApi();

    @AfterAll
    static void stop() {
        API.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("costguard.gateway.egress.spark.url", () -> API.url().toString());
        registry.add("costguard.gateway.egress.spark.vendor", () -> "SPARK");
        registry.add("costguard.gateway.egress.spark.header", () -> "Authorization");
        registry.add("costguard.gateway.egress.spark.value", () -> "Bearer spark-secret");
        registry.add("costguard.gateway.egress.spark.price", () -> "15");
        registry.add("costguard.gateway.egress.spark.operations[0].path", () -> "/v1/companies/{inn}/report");
        registry.add("costguard.gateway.egress.spark.operations[0].product", () -> "report");
        registry.add("costguard.gateway.egress.spark.operations[0].price", () -> "450");
        registry.add("costguard.gateway.egress.cloud.url", () -> API.url().toString());
        registry.add("costguard.gateway.egress.cloud.vendor", () -> "Cloud");
    }

    @Autowired PriceBook prices;
    @Autowired RateBook rates;
    @Autowired TaskFitAssessor assessor;

    @BeforeEach
    void setUp() {
        agentWithBudget("900");
        API.received.clear();
        API.status = 200;
        API.response = "{\"inn\":\"7707083893\",\"name\":\"ПАО Сбербанк\"}";
    }

    private WebTestClient.ResponseSpec call(String api, String path, UUID operation) {
        return web.get().uri("/v1/egress/" + api + path).header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", operation.toString()).header("X-CostGuard-Task", "ticket-7").exchange();
    }

    @Test
    void aCallIsReservedProxiedWithTheApiKeyAndCharged() {
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/7707083893/report", op).expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("Сбербанк"));

        FakeProvider.Received sent = API.received.getFirst();
        assertThat(sent.path()).isEqualTo("/v1/companies/7707083893/report");
        assertThat(sent.headers().get("Authorization")).containsExactly("Bearer spark-secret");
        assertThat(sent.headers().keySet()).noneMatch(h -> h.equalsIgnoreCase("Idempotency-Key")
                || h.toLowerCase().startsWith("x-costguard"));
        assertThat(reserve.settled.get(op.toString())).isEqualByComparingTo("450");
        assertThat(reserve.reserved).doesNotContainKey(op.toString());
        assertThat(recorded(op)).singleElement().satisfies(e -> {
            assertThat(e.getDecision()).isEqualTo(OperationDecision.ALLOWED);
            assertThat(e.getKind()).isEqualTo(OperationKind.PAID_API);
            assertThat(e.getProvider()).isEqualTo("SPARK");
            assertThat(e.getModel()).isEqualTo("report");
            assertThat(e.getTaskId()).isEqualTo("ticket-7");
            assertThat(e.getActualCost()).isEqualByComparingTo("450");
            assertThat(e.getMandateVersion()).isEqualTo(3);
        });
    }

    @Test
    void theTenantCatalogPricesTheCall() {
        prices.apply(CatalogPrice.newBuilder().setTenantId(tenant).setSkuId("spark-report").setProvider("SPARK")
                .setModel("report").setUnit(PriceUnit.REQUEST).setUnitPrice(money("380")).setCurrency("RUB")
                .setValidFrom(LocalDate.of(2026, 1, 1)).build());
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/1/report", op).expectStatus().isOk();

        assertThat(reserve.settled.get(op.toString())).isEqualByComparingTo("380");
    }

    @Test
    void anApiErrorGivesTheReserveBackAndIsRecorded() {
        API.status = 503;
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/1/report", op).expectStatus().isEqualTo(503);

        assertThat(reserve.released).contains(op.toString());
        assertThat(reserve.settled).doesNotContainKey(op.toString());
        assertThat(recorded(op)).singleElement().satisfies(e -> {
            assertThat(e.getDecision()).isEqualTo(OperationDecision.DENIED);
            assertThat(e.getDenialCode()).isEqualTo("upstream_error");
            assertThat(e.getActualCost()).isEqualByComparingTo("0");
            assertThat(e.getEstimatedCost()).isEqualByComparingTo("450");
        });
    }

    @Test
    void aCallWithNoPriceIsRefusedBeforeItLeaves() {
        UUID op = UUID.randomUUID();

        call("cloud", "/v1/vm", op).expectStatus().isForbidden()
                .expectBody(String.class).value(b -> assertThat(b).contains("price_unknown"));

        assertThat(API.received).isEmpty();
        assertThat(recorded(op)).singleElement()
                .satisfies(e -> assertThat(e.getDenialCode()).isEqualTo("price_unknown"));
    }

    @Test
    void aCallThatDoesNotFitTheBudgetIsRefusedBeforeItLeaves() {
        reserve.left = new BigDecimal("100");
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/1/report", op).expectStatus().isEqualTo(402);

        assertThat(API.received).isEmpty();
        assertThat(recorded(op)).singleElement()
                .satisfies(e -> assertThat(e.getDenialCode()).isEqualTo("budget_exceeded"));
    }

    @Test
    void aRepeatedOperationIdGetsTheFirstAnswerAndNothingLeaves() {
        UUID op = UUID.randomUUID();
        call("spark", "/v1/companies/1/report", op).expectStatus().isOk();
        API.received.clear();

        call("spark", "/v1/companies/1/report", op).expectStatus().isOk().expectHeader()
                .valueEquals("X-CostGuard-Replayed", "true");

        assertThat(API.received).isEmpty();
    }

    @Test
    void aStrangerGets401AndNothingLeaves() {
        web.get().uri("/v1/egress/spark/v1/companies/1/report").header("Authorization", "Bearer cg_live_nobody")
                .exchange().expectStatus().isUnauthorized();
        assertThat(API.received).isEmpty();
    }

    /** ADR 0072: the configuration prices SPARK in roubles; a company in dollars is charged them converted. */
    @Test
    void aCompanyInDollarsPaysTheRoublePriceInDollars() {
        agentWithBudget("900", "USD");
        rates.apply(ExchangeRate.newBuilder().setCurrency("USD").setRoubles(new BigDecimal("80.00000000"))
                .setRateDate(LocalDate.of(2026, 11, 7)).build());
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/1/report", op).expectStatus().isOk();

        assertThat(reserve.settled.get(op.toString())).isEqualByComparingTo("5.625");
        assertThat(recorded(op)).singleElement().satisfies(e -> {
            assertThat(e.getCurrency()).isEqualTo("USD");
            assertThat(e.getActualCost()).isEqualByComparingTo("5.625");
        });
    }

    /** Before ADR 0072 every operation said RUB, and a dollar price was charged as roubles. */
    @Test
    void anOperationSaysTheCurrencyOfTheBudgets() {
        UUID op = UUID.randomUUID();
        call("spark", "/v1/companies/1/report", op).expectStatus().isOk();
        assertThat(recorded(op)).singleElement().satisfies(e -> assertThat(e.getCurrency()).isEqualTo("RUB"));
    }

    @Test
    void aCatalogPriceInACurrencyWithoutARateIsNoPrice() {
        prices.apply(CatalogPrice.newBuilder().setTenantId(tenant).setSkuId("spark-report").setProvider("SPARK")
                .setModel("report").setUnit(PriceUnit.REQUEST).setUnitPrice(money("5")).setCurrency("CHF")
                .setValidFrom(LocalDate.of(2026, 1, 1)).build());
        UUID op = UUID.randomUUID();

        call("spark", "/v1/companies/1/report", op).expectStatus().isForbidden()
                .expectBody(String.class).value(b -> assertThat(b).contains("price_unknown"));

        assertThat(API.received).isEmpty();
    }

    /** Without assessor settings a person decides above the threshold. */
    @Test
    void withoutSettingsTheAssessorIsHumanOnly() {
        assertThat(assessor).isInstanceOf(HumanOnlyAssessor.class);
    }
}
