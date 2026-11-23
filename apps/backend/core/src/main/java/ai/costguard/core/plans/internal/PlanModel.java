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

/**
 * One plan of the catalog. {@code monthlyPrice} is null for the trial. {@code prices}: the monthly price in every
 * currency the plan is sold in (ADR 0077), the main one among them.
 */
record PlanOffer(String name, String title, BigDecimal monthlyPrice, String currency, PlanLimitsView limits,
        java.util.Map<String, BigDecimal> prices) {
}

enum PlanStatus { trial, active, read_only }

/**
 * The company's plan: {@code status} read_only after the trial or the paid period ended. {@code subscription}: the
 * subscription at the payment provider (ADR 0077), null without one.
 */
record CurrentPlan(String plan, String title, PlanStatus status, Instant trialEndsAt, Instant accessUntil,
        PlanLimitsView limits, SubscriptionView subscription) {
}

/**
 * {@code status} as the provider says: active, past_due (a renewal failed, the provider retries), canceled and
 * others. {@code cancelAtPeriodEnd}: cancelled, works until {@code paidUntil}.
 */
record SubscriptionView(String provider, String status, boolean cancelAtPeriodEnd, Instant paidUntil) {
}

/** {@code currency}: one the plan is sold in; empty: the plan's main currency. */
record CheckoutRequest(@NotBlank String plan, @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}")
        String currency) {
}

/** Where the owner changes or cancels the subscription, the card, and reads the invoices. */
record PortalView(String url) {
}

/** A payment of the company: one it started, or a renewal the provider charged by itself. */
record PaymentHistoryRow(UUID id, String plan, BigDecimal amount, String currency, String status,
        Instant createdAt, Instant paidAt) {
}

record SubscriptionRow(UUID tenantId, String provider, String subscriptionId, String customerId, String plan,
        String status, boolean cancelAtPeriodEnd, Instant paidUntil, Instant statusAt) {

    /** A subscription the provider still charges or retries: a second one must not be started next to it. */
    boolean live() {
        return switch (status) {
            case "active", "trialing", "past_due", "unpaid", "incomplete", "paused" -> true;
            default -> false;
        };
    }
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
