package ai.costguard.core.plans.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment provider (ADR 0038): {@link DummyPaymentProvider} for the local stack, {@link StripePaymentProvider}
 * for subscriptions (ADR 0077). An implementation is a bean whose {@link #name()} is set in
 * {@code costguard.plans.payments.provider}; its keys come from the environment, never from the code.
 *
 * <p>The flow is the one every acquiring API has: the core creates a payment and gets a page to send the payer to,
 * then the provider tells the core how it ended, with a notice the core must verify.
 */
interface PaymentProvider {

    String name();

    /**
     * Creates the payment at the provider. {@code paymentId} is the core's id and doubles as the idempotency key,
     * so a retried call does not charge twice.
     */
    Checkout start(PaymentRequest request);

    /**
     * Reads a notice the provider sent to {@code POST /api/v1/billing/notifications/{provider}}. Empty when it is
     * not genuine (a wrong signature, an unknown sender) or not about a payment: the core then changes nothing.
     * Header names come in lower case.
     */
    Optional<Notice> notice(Map<String, String> headers, String body);

    /**
     * A page of the provider where the customer changes or cancels the subscription, the card, and reads the
     * invoices (ADR 0077). Empty for a provider without one.
     */
    default Optional<String> portal(String customerId, String returnUrl) {
        return Optional.empty();
    }

    enum Status { PENDING, SUCCEEDED, CANCELED }

    /** {@code customerId}: the company's customer at the provider from an earlier subscription, or null. */
    record PaymentRequest(UUID paymentId, UUID tenantId, String plan, BigDecimal amount, String currency,
            String description, String returnUrl, String customerId) {

        PaymentRequest(UUID paymentId, UUID tenantId, String plan, BigDecimal amount, String currency,
                String description, String returnUrl) {
            this(paymentId, tenantId, plan, amount, currency, description, returnUrl, null);
        }
    }

    /** {@code confirmationUrl}: where to send the payer; null when the payment needs no step from them. */
    record Checkout(String providerPaymentId, Status status, String confirmationUrl) {
    }

    /**
     * A verified notice. {@code providerPaymentId}: the payment the core started, or null for an event about the
     * subscription only. {@code eventId}: the provider's id of the delivery, so that a repeated one changes nothing;
     * null for a provider that repeats only harmless notices. {@code at}: when the provider made the event.
     * Neither a payment nor a subscription: an event the core does not act on.
     */
    record Notice(String providerPaymentId, Status status, String eventId, java.time.Instant at,
            SubscriptionChange subscription) {

        Notice(String providerPaymentId, Status status) {
            this(providerPaymentId, status, null, null, null);
        }

        static Notice ignored(String eventId) {
            return new Notice(null, null, eventId, null, null);
        }
    }

    /**
     * What happened to a subscription. {@code plan}: the plan of its price, null when the price is not one of the
     * catalog's. {@code periodEnd}: the end of the period the event is about. {@code amount} and {@code currency}
     * for a paid invoice; {@code reference} is the provider's id of that invoice. {@code first}: the invoice of the
     * checkout itself, already recorded as the core's payment.
     */
    record SubscriptionChange(Kind kind, String subscriptionId, String customerId, String plan, String status,
            boolean cancelAtPeriodEnd, java.time.Instant periodEnd, BigDecimal amount, String currency,
            String reference, boolean first) {

        enum Kind { STARTED, PAID, PAYMENT_FAILED, UPDATED, ENDED }
    }
}
