package ai.costguard.core.plans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which provider takes payments (ADR 0038): none unless one is named, and never one the build does not have. */
class PaymentsTest {

    private static PlansProperties properties(String provider) {
        PlansProperties.Plan plan = new PlansProperties.Plan("P", BigDecimal.TEN, "RUB",
                new PlansProperties.Limits(null, null, null, null), null);
        return new PlansProperties(Period.ofDays(14), Period.ofDays(3), Period.ofMonths(1), Duration.ofMinutes(15),
                Map.of("trial", plan, "start", plan, "business", plan),
                payments(provider));
    }

    @Test
    void withoutASettingThereAreNoPayments() {
        Payments payments = new Payments(List.of(new DummyPaymentProvider()), properties(""));

        assertThat(payments.provider()).isEmpty();
        assertThat(payments.byName("dummy")).as("the dummy's notice URL is closed too").isEmpty();
    }

    @Test
    void theDummyTakesNoMoneyAndHearsNoNotices() {
        Payments payments = new Payments(List.of(new DummyPaymentProvider()), properties("dummy"));
        PaymentProvider dummy = payments.provider().orElseThrow();
        UUID id = UUID.randomUUID();

        PaymentProvider.Checkout checkout = dummy.start(new PaymentProvider.PaymentRequest(id, UUID.randomUUID(),
                "start", BigDecimal.TEN, "RUB", "CostGuard, Start", "http://localhost:3000"));

        assertThat(checkout.status()).isEqualTo(PaymentProvider.Status.SUCCEEDED);
        assertThat(checkout.providerPaymentId()).isEqualTo("dummy-" + id);
        assertThat(checkout.confirmationUrl()).isNull();
        assertThat(dummy.notice(Map.of(), "SUCCEEDED:dummy-" + id)).isEmpty();
    }

    @Test
    void aProviderTheBuildDoesNotHaveStopsTheStart() {
        assertThatThrownBy(() -> new Payments(List.of(new DummyPaymentProvider()), properties("yookassa")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("yookassa");
    }

    static PlansProperties.Payments payments(String provider) {
        return new PlansProperties.Payments(provider, "http://localhost:3000/settings/plan", Period.ofDays(7),
                new PlansProperties.Stripe("", "", "https://api.stripe.com", Duration.ofMinutes(5), false, null));
    }

    @Test
    void everyPlanMustBeConfigured() {
        assertThatThrownBy(() -> new PlansProperties(Period.ofDays(14), Period.ofDays(3), Period.ofMonths(1),
                Duration.ofMinutes(15), Map.of(), payments("")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("costguard.plans.catalog.trial");
    }
}
