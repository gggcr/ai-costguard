package ai.costguard.core.plans.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment provider (ADR 0038). The provider is not chosen yet: only {@link DummyPaymentProvider} exists. A real
 * one implements this interface as a bean whose {@link #name()} is set in {@code costguard.plans.payments.provider};
 * its keys come from the environment, never from the code.
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

    enum Status { PENDING, SUCCEEDED, CANCELED }

    record PaymentRequest(UUID paymentId, UUID tenantId, String plan, BigDecimal amount, String currency,
            String description, String returnUrl) {
    }

    /** {@code confirmationUrl}: where to send the payer; null when the payment needs no step from them. */
    record Checkout(String providerPaymentId, Status status, String confirmationUrl) {
    }

    record Notice(String providerPaymentId, Status status) {
    }
}
