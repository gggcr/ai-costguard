package ai.costguard.core.plans.internal;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A provider that takes no money: every payment succeeds at once (ADR 0038). For the local stack and tests while
 * no real provider is chosen. It is used only when {@code costguard.plans.payments.provider} is {@code dummy}, so a
 * deployment that forgets the setting has no payments at all rather than free plans.
 */
@Component
class DummyPaymentProvider implements PaymentProvider {

    static final String NAME = "dummy";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Checkout start(PaymentRequest request) {
        return new Checkout("dummy-" + request.paymentId(), Status.SUCCEEDED, null);
    }

    /** It never sends notices, so none is genuine: a request to its notice URL changes nothing. */
    @Override
    public Optional<Notice> notice(Map<String, String> headers, String body) {
        return Optional.empty();
    }
}
