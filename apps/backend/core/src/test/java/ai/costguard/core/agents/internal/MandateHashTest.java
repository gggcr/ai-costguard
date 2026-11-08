package ai.costguard.core.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The canonical form of ADR 0009: anyone checking must be able to recompute the hash on the chain. */
class MandateHashTest {

    private static final UUID TENANT = UUID.fromString("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a");
    private static final UUID MANDATE = UUID.fromString("0b9d2c7e-4a51-4c1e-8f0a-3d2b1c4e5f60");
    private static final String EXPRESSION = "vendor in [\"OpenAI\"] && cost <= 500.0";

    @Test
    void matchesTheFormOfTheAdr() {
        // python: sha256("\n".join(["costguard:mandate:v1", tenant, mandate, "2", "300.000000", expression]))
        assertThat(MandateHash.of(TENANT, MANDATE, 2, new BigDecimal("300"), EXPRESSION))
                .isEqualTo("1bb75ba958cc6c78446a512c8ef8d89c4ebc49a9a1c68faeb66cee01b80da64b");
    }

    @Test
    void theScaleOfTheThresholdDoesNotChangeTheHash() {
        assertThat(MandateHash.of(TENANT, MANDATE, 2, new BigDecimal("300.0"), EXPRESSION))
                .isEqualTo(MandateHash.of(TENANT, MANDATE, 2, new BigDecimal("300.000000"), EXPRESSION));
    }

    @Test
    void everyPartCounts() {
        String base = MandateHash.of(TENANT, MANDATE, 2, BigDecimal.TEN, EXPRESSION);
        assertThat(MandateHash.of(UUID.randomUUID(), MANDATE, 2, BigDecimal.TEN, EXPRESSION)).isNotEqualTo(base);
        assertThat(MandateHash.of(TENANT, MANDATE, 3, BigDecimal.TEN, EXPRESSION)).isNotEqualTo(base);
        assertThat(MandateHash.of(TENANT, MANDATE, 2, BigDecimal.ONE, EXPRESSION)).isNotEqualTo(base);
        assertThat(MandateHash.of(TENANT, MANDATE, 2, BigDecimal.TEN, EXPRESSION + " ")).isNotEqualTo(base);
        assertThat(base).matches("[0-9a-f]{64}");
    }
}
