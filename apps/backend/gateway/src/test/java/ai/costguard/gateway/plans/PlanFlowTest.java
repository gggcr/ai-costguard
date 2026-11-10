package ai.costguard.gateway.plans;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.OperationDecision;
import ai.costguard.events.TenantPlan;
import ai.costguard.gateway.support.InMemoryGatewayTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The plan's part of step 1 through the whole gateway (ADR 0033): a read-only company spends nothing, the operations
 * of the month are counted only when an operation passes the whole ladder, and the refusal says what to do.
 */
class PlanFlowTest extends InMemoryGatewayTest {

    @BeforeEach
    void setUp() {
        agentWithBudget("900");
    }

    private void plan(boolean readOnly, Long operationsPerMonth, long version) {
        plans.apply(TenantPlan.newBuilder().setTenantId(tenant).setPlan(readOnly ? "trial" : "start")
                .setReadOnly(readOnly).setOperationsPerMonth(operationsPerMonth).setAccessUntil(null)
                .setVersion(version).setUpdatedAt(Instant.now()).build());
    }

    private WebTestClient.ResponseSpec authorize(UUID op, String amount) {
        return web.post().uri("/v1/spend/authorize")
                .header("Authorization", "Bearer " + secret).header("Idempotency-Key", op.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vendor", "Ozon", "kind", "paid_api", "product", "Бумага",
                        "amount", new BigDecimal(amount)))
                .exchange();
    }

    private long used() {
        return QUOTA.used(tenant, OperationQuota.month(Instant.now()));
    }

    @Test
    void aCompanyWithoutAPlanIsNotLimitedOrCounted() {
        authorize(UUID.randomUUID(), "10").expectStatus().isOk();

        assertThat(used()).isZero();
    }

    @Test
    void aReadOnlyCompanySpendsNothingAndIsToldWhy() {
        plan(true, 1000L, 1);
        UUID op = UUID.randomUUID();

        authorize(op, "10").expectStatus().isEqualTo(402).expectBody()
                .jsonPath("$.error.type").isEqualTo("plan_read_only")
                .jsonPath("$.error.message").isEqualTo(PlanGate.READ_ONLY);

        assertThat(reserve.reserved).doesNotContainKey(op.toString());
        assertThat(recorded(op)).singleElement().satisfies(e -> {
            assertThat(e.getDecision()).isEqualTo(OperationDecision.DENIED);
            assertThat(e.getDenialCode()).isEqualTo("plan_read_only");
        });
    }

    @Test
    void theOperationsOfTheMonthEndAtThePlansLimit() {
        plan(false, 2L, 1);

        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
        authorize(UUID.randomUUID(), "10").expectStatus().isEqualTo(402).expectBody()
                .jsonPath("$.error.type").isEqualTo("plan_limit_exceeded")
                .jsonPath("$.error.message").isEqualTo(PlanGate.USED_UP.formatted(2L));

        assertThat(used()).isEqualTo(2);
    }

    @Test
    void anOperationRefusedLaterGivesItsPlaceBack() {
        plan(false, 1L, 1);

        // The mandate allows up to 1000: step 2 refuses after step 1 counted the operation.
        authorize(UUID.randomUUID(), "5000").expectStatus().isForbidden();
        assertThat(used()).isZero();

        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void aBiggerPlanLetsTheCompanyGoOnAndAnOlderMessageChangesNothing() {
        plan(false, 1L, 1);
        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
        authorize(UUID.randomUUID(), "10").expectStatus().isEqualTo(402);

        plan(false, 5L, 2);
        authorize(UUID.randomUUID(), "10").expectStatus().isOk();

        plan(true, 5L, 1); // redelivered old version
        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
    }

    @Test
    void aTombstoneForgetsThePlan() {
        plan(true, null, 1);
        plans.remove(tenant);

        authorize(UUID.randomUUID(), "10").expectStatus().isOk();
    }
}
