package ai.costguard.core.agents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.platform.rules.OperationFacts;
import ai.costguard.platform.rules.OperationRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The expression is what the gateway enforces, so it is checked by actually evaluating it. */
class MandateExpressionTest {

    private static boolean allows(String hours, String model, String vendor, double cost, String time, long weekday) {
        return allows("LLM_CALL", hours, model, vendor, cost, time, weekday);
    }

    private static boolean allows(String kind, String hours, String model, String vendor, double cost, String time,
            long weekday) {
        Mandate mandate = new Mandate(UUID.randomUUID(), "m", List.of("gpt-5", "a\"b"), List.of("OpenAI, Inc"),
                new BigDecimal("500"), BigDecimal.ONE, hours, MandateStatus.published, 1, Instant.EPOCH);
        return OperationRules.compile(MandateExpression.of(mandate))
                .matches(new OperationFacts("a", kind, vendor, model, cost, time, weekday));
    }

    /** A purchase has no model; the models of the mandate bind LLM calls only. */
    @org.junit.jupiter.api.Test
    void aPurchaseNeedsNoModel() {
        assertThat(allows("PAID_API", "круглосуточно", null, "OpenAI, Inc", 100, "03:00", 7)).isTrue();
        assertThat(allows("PAID_API", "круглосуточно", null, "Ozon", 100, "03:00", 7)).isFalse();
        assertThat(allows("PAID_API", "круглосуточно", null, "OpenAI, Inc", 501, "03:00", 7)).isFalse();
        assertThat(allows("LLM_CALL", "круглосуточно", null, "OpenAI, Inc", 100, "03:00", 7)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "круглосуточно      | gpt-5   | OpenAI, Inc | 500 | 03:00 | 7 | true",
            "круглосуточно      | gpt-5   | OpenAI, Inc | 501 | 03:00 | 7 | false",
            "круглосуточно      | gpt-4   | OpenAI, Inc | 1   | 03:00 | 7 | false",
            "круглосуточно      | a\"b    | OpenAI, Inc | 1   | 03:00 | 7 | true",
            "круглосуточно      | gpt-5   | OpenAI      | 1   | 03:00 | 7 | false",
            "09:00–21:00        | gpt-5   | OpenAI, Inc | 1   | 09:00 | 1 | true",
            "09:00–21:00        | gpt-5   | OpenAI, Inc | 1   | 21:00 | 1 | false",
            "22:00–06:00        | gpt-5   | OpenAI, Inc | 1   | 23:30 | 1 | true",
            "22:00–06:00        | gpt-5   | OpenAI, Inc | 1   | 05:59 | 1 | true",
            "22:00–06:00        | gpt-5   | OpenAI, Inc | 1   | 12:00 | 1 | false",
            "только рабочие дни | gpt-5   | OpenAI, Inc | 1   | 12:00 | 5 | true",
            "только рабочие дни | gpt-5   | OpenAI, Inc | 1   | 12:00 | 6 | false",
    })
    void enforcesTheMandate(String hours, String model, String vendor, double cost, String time, long weekday,
            boolean allowed) {
        assertThat(allows(hours.strip(), model.strip(), vendor.strip(), cost, time, weekday)).isEqualTo(allowed);
    }
}
