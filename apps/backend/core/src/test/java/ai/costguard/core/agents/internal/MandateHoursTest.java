package ai.costguard.core.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An English API client may name the hours of a mandate in English; they are stored as the gateway reads them. */
class MandateHoursTest {

    private static MandateDetails with(String hours) {
        return new MandateDetails("m", List.of("gpt-5"), List.of("Acme"), BigDecimal.TEN, BigDecimal.ONE, hours);
    }

    @Test
    void englishNamesAreStoredAsTheGatewayReadsThem() {
        assertThat(with("24/7").hours()).isEqualTo(MandateExpression.ALL_DAY);
        assertThat(with("Workdays").hours()).isEqualTo(MandateExpression.WORKDAYS);
        assertThat(with("09:00-18:00").hours()).isEqualTo("09:00-18:00");
        assertThat(with(MandateExpression.ALL_DAY).hours()).isEqualTo(MandateExpression.ALL_DAY);
        // and the API answers in codes, whatever was sent
        assertThat(MandateExpression.code(MandateExpression.ALL_DAY)).isEqualTo("24/7");
        assertThat(MandateExpression.code(MandateExpression.WORKDAYS)).isEqualTo("workdays");
        assertThat(MandateExpression.code("09:00–18:00")).isEqualTo("09:00–18:00");
        assertThat("24/7").matches(MandateExpression.HOURS);
        assertThat("Workdays").matches(MandateExpression.HOURS);
    }
}
