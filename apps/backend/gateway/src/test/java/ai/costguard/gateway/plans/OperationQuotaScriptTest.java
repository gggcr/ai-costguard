package ai.costguard.gateway.plans;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The keys of the monthly quota; the scripts run on real Redis in {@link OperationQuotaIT}. The key carries the
 * {tenant} hash tag like every budget key, so a script touches one cluster slot, and the month is the UTC month.
 */
class OperationQuotaScriptTest {

    @Test
    void theKeyStaysInTheTenantsSlotAndNamesTheMonth() {
        UUID tenant = UUID.fromString("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a");

        assertThat(OperationQuota.key(tenant, "2026-11")).isEqualTo("cg:{" + tenant + "}:ops:2026-11");
    }

    @Test
    void theMonthIsTheUtcCalendarMonth() {
        assertThat(OperationQuota.month(Instant.parse("2026-11-30T23:59:59Z"))).isEqualTo("2026-11");
        assertThat(OperationQuota.month(Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo("2026-12");
    }
}
