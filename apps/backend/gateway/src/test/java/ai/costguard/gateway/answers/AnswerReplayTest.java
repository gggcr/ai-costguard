package ai.costguard.gateway.answers;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.gateway.support.FakeProvider;
import ai.costguard.gateway.support.InMemoryGatewayTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** A retry with the same Idempotency-Key gets the first answer, and is checked, reserved and charged once. */
class AnswerReplayTest extends InMemoryGatewayTest {

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
    }

    @BeforeEach
    void setUp() {
        agentWithBudget("900");
        API.received.clear();
        API.status = 200;
        API.response = "{\"inn\":\"7707083893\",\"name\":\"ПАО Сбербанк\"}";
    }

    private WebTestClient.ResponseSpec call(UUID operation) {
        return web.get().uri("/v1/egress/spark/v1/companies/7707083893/report").header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", operation.toString()).exchange();
    }

    private WebTestClient.ResponseSpec authorize(UUID operation) {
        return web.post().uri("/v1/spend/authorize").header("Authorization", "Bearer " + secret)
                .header("Idempotency-Key", operation.toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vendor", "SPARK", "amount", 300)).exchange();
    }

    @Test
    void aRetriedCallGetsTheFirstAnswerAndIsChargedOnce() {
        UUID op = UUID.randomUUID();
        String first = call(op).expectStatus().isOk().expectHeader().doesNotExist("X-CostGuard-Replayed")
                .expectBody(String.class).returnResult().getResponseBody();

        String again = call(op).expectStatus().isOk().expectHeader().valueEquals("X-CostGuard-Replayed", "true")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(again).isEqualTo(first).contains("Сбербанк");
        assertThat(API.received).hasSize(1);
        assertThat(reserve.settled.get(op.toString())).isEqualByComparingTo("15");
        assertThat(recorded(op)).hasSize(1);
    }

    @Test
    void aRetriedAuthorizationGetsTheSameAnswerAndReservesNothingMore() {
        UUID op = UUID.randomUUID();
        authorize(op).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("authorized");
        authorize(op).expectStatus().isOk().expectHeader().valueEquals("X-CostGuard-Replayed", "true")
                .expectBody().jsonPath("$.status").isEqualTo("authorized")
                .jsonPath("$.authorizationId").isEqualTo(op.toString());
        assertThat(reserve.reserved).containsOnlyKeys(op.toString());
    }

    @Test
    void afterTheSettlementARetriedAuthorizationGets409() {
        UUID op = UUID.randomUUID();
        authorize(op).expectStatus().isOk();
        web.post().uri("/v1/spend/settle").header("Authorization", "Bearer " + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("authorizationId", op.toString(), "amount", new BigDecimal("5"))).exchange()
                .expectStatus().isOk();
        // the authorization is spent: handing it out again would be a lie
        authorize(op).expectStatus().isEqualTo(409).expectBody().jsonPath("$.error.type")
                .isEqualTo("duplicate_operation");
    }

    @Test
    void anAnswerTooBigToKeepStillGets409() {
        UUID op = UUID.randomUUID();
        ANSWERS.limit = 16; // the answer of the API is longer
        call(op).expectStatus().isOk();
        call(op).expectStatus().isEqualTo(409).expectBody().jsonPath("$.error.type").isEqualTo("duplicate_operation");
        assertThat(API.received).hasSize(1);
    }

    @Test
    void aFailedCallKeepsNothing() {
        UUID op = UUID.randomUUID();
        API.status = 500;
        call(op).expectStatus().isEqualTo(500);
        assertThat(ANSWERS.kept).doesNotContainKey(AnswerCache.key(tenant, op));
        // nothing was reserved or settled: the retry is a new attempt
        API.status = 200;
        call(op).expectStatus().isOk().expectHeader().doesNotExist("X-CostGuard-Replayed");
        assertThat(new String(ANSWERS.kept.get(AnswerCache.key(tenant, op)).body(), StandardCharsets.UTF_8))
                .contains("Сбербанк");
    }
}
