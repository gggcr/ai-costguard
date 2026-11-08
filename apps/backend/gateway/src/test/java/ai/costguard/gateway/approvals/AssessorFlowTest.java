package ai.costguard.gateway.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.OperationDecision;
import ai.costguard.gateway.support.FakeProvider;
import ai.costguard.gateway.support.InMemoryGatewayTest;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Step 6 with the AI assessor on, through the whole gateway (ADR 0011). The model is a stand-in for the provider:
 * "yes" passes, "no" or "unsure" goes to a person, and so do a failure and a timeout of the model.
 */
class AssessorFlowTest extends InMemoryGatewayTest {

    static final FakeProvider LLM = fakeApi();

    @AfterAll
    static void stop() {
        LLM.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("costguard.gateway.providers.openai.url", () -> LLM.url().toString());
        registry.add("costguard.gateway.providers.openai.api-key", () -> "sk-assessor-key");
        registry.add("costguard.gateway.assessor.provider", () -> "openai");
        registry.add("costguard.gateway.assessor.model", () -> "gpt-5-mini");
        registry.add("costguard.gateway.assessor.timeout", () -> "PT0.5S");
    }

    @Autowired TaskFitAssessor assessor;

    private static final String GOAL = "Купить картриджи для принтеров бухгалтерии";

    @BeforeEach
    void setUp() {
        agentWithBudget("900");
        LLM.received.clear();
        LLM.status = 200;
        LLM.delayMillis = 0;
        answer("ДА");
    }

    private static void answer(String text) {
        LLM.response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    private WebTestClient.ResponseSpec authorize(UUID op, String amount, String goal) {
        WebTestClient.RequestBodySpec request = web.post().uri("/v1/spend/authorize")
                .header("Authorization", "Bearer " + secret).header("Idempotency-Key", op.toString())
                .contentType(MediaType.APPLICATION_JSON);
        if (goal != null) {
            request = request.header("X-CostGuard-Task-Goal", URLEncoder.encode(goal, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return request.bodyValue(Map.of("vendor", "Ozon", "kind", "paid_api", "product", "Картридж HP 85A",
                "amount", new BigDecimal(amount))).exchange();
    }

    private void heldForAPerson(UUID op) {
        assertThat(recorded(op)).singleElement().satisfies(e -> {
            assertThat(e.getDecision()).isEqualTo(OperationDecision.PENDING_APPROVAL);
            assertThat(e.getDenialCode()).isEqualTo("above_threshold");
        });
        assertThat(reserve.reserved).doesNotContainKey(op.toString());
    }

    @Test
    void theAssessorIsTheModel() {
        assertThat(assessor).isInstanceOf(LlmTaskFitAssessor.class);
    }

    @Test
    void aFittingOperationPassesWithoutAPerson() {
        UUID op = UUID.randomUUID();

        authorize(op, "950", GOAL).expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("authorized");

        assertThat(reserve.reserved.get(op.toString())).isEqualByComparingTo("950");
        FakeProvider.Received asked = LLM.received.getFirst();
        assertThat(asked.path()).isEqualTo("/v1/chat/completions");
        assertThat(asked.headers().get("Authorization")).containsExactly("Bearer sk-assessor-key");
        assertThat(asked.body()).contains(GOAL).contains("Картридж HP 85A").contains("950").contains("gpt-5-mini");
        assertThat(asked.body()).doesNotContain(secret);
        // the assessor's own call is not the company's: the agent has no event besides its operation
        assertThat(log.recorded).noneMatch(e -> e.getAgentId().equals(agent) && !e.getOperationId().equals(op));
    }

    @Test
    void aNoGoesToAPerson() {
        answer("НЕТ");
        UUID op = UUID.randomUUID();

        authorize(op, "950", GOAL).expectStatus().isForbidden().expectBody()
                .jsonPath("$.error.type").isEqualTo("approval_required")
                .jsonPath("$.error.approvalId").isEqualTo(op.toString());

        heldForAPerson(op);
    }

    @Test
    void anUnsureAnswerGoesToAPerson() {
        answer("Не уверен, данных мало");
        UUID op = UUID.randomUUID();

        authorize(op, "950", GOAL).expectStatus().isForbidden();

        heldForAPerson(op);
    }

    @Test
    void aFailureOfTheModelGoesToAPerson() {
        LLM.status = 500;
        UUID op = UUID.randomUUID();

        authorize(op, "950", GOAL).expectStatus().isForbidden().expectBody()
                .jsonPath("$.error.type").isEqualTo("approval_required");

        heldForAPerson(op);
    }

    @Test
    void aTimeoutOfTheModelGoesToAPerson() {
        LLM.delayMillis = 2000;
        UUID op = UUID.randomUUID();

        authorize(op, "950", GOAL).expectStatus().isForbidden().expectBody()
                .jsonPath("$.error.type").isEqualTo("approval_required");

        heldForAPerson(op);
    }

    @Test
    void withoutAGoalThereIsNothingToAskAndAPersonDecides() {
        UUID op = UUID.randomUUID();

        authorize(op, "950", null).expectStatus().isForbidden();

        assertThat(LLM.received).isEmpty();
        heldForAPerson(op);
    }

    @Test
    void belowTheThresholdTheModelIsNotAsked() {
        authorize(UUID.randomUUID(), "500", GOAL).expectStatus().isOk();

        assertThat(LLM.received).isEmpty();
    }
}
