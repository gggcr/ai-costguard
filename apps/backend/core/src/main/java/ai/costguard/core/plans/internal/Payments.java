package ai.costguard.core.plans.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The provider {@code costguard.plans.payments.provider} names, among the implementations on the classpath. */
@Component
class Payments {

    private final List<PaymentProvider> providers;
    private final String configured;

    Payments(List<PaymentProvider> providers, PlansProperties properties) {
        this.providers = List.copyOf(providers);
        this.configured = properties.payments().provider().strip();
        if (!configured.isEmpty() && byName(configured).isEmpty()) {
            throw new IllegalStateException("costguard.plans.payments.provider names " + configured
                    + ", which is not a payment provider of this build");
        }
    }

    /** The configured provider; empty while payments are off. */
    Optional<PaymentProvider> provider() {
        return configured.isEmpty() ? Optional.empty() : byName(configured);
    }

    /** Only the configured provider takes notices: another one's notice URL must not apply payments. */
    Optional<PaymentProvider> byName(String name) {
        if (configured.isEmpty() || !configured.equals(name)) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
