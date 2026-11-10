package ai.costguard.core.plans.internal;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A company's plan as stored. {@code accessUntil} null: no end. */
record PlanState(UUID tenantId, String plan, Instant trialEndsAt, Instant accessUntil, long version) {

    boolean readOnly(Instant now) {
        return accessUntil != null && !now.isBefore(accessUntil);
    }
}

/** A limit left null is no limit. */
record PlanLimitsView(Long agents, Long employees, Long connectedSystems, Long operationsPerMonth) {
}

/** One plan of the catalog. {@code monthlyPrice} is null for the trial. */
record PlanOffer(String name, String title, BigDecimal monthlyPrice, String currency, PlanLimitsView limits) {
}

enum PlanStatus { trial, active, read_only }

/** The company's plan: {@code status} read_only after the trial or the paid period ended. */
record CurrentPlan(String plan, String title, PlanStatus status, Instant trialEndsAt, Instant accessUntil,
        PlanLimitsView limits) {
}

record CheckoutRequest(@NotBlank String plan) {
}

/** {@code confirmationUrl}: where to send the payer; null when the payment already succeeded. */
record CheckoutView(UUID paymentId, String status, String confirmationUrl, CurrentPlan plan) {
}

record PaymentRow(UUID id, UUID tenantId, String plan, BigDecimal amount, String currency, String provider,
        String providerPaymentId, String status) {
}

record PaymentRef(UUID tenantId, UUID paymentId) {
}

record ScheduleRow(UUID tenantId, Instant accessUntil, Instant warnedFor, Instant endedFor) {
}
