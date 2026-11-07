package ai.costguard.gateway.metering;

import ai.costguard.events.PriceUnit;
import ai.costguard.gateway.rules.PriceBook;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Price of a call from the catalog: input tokens, output tokens and a per-request fee where one is set. The
 * reservation uses the most the call may cost, which is why {@code max_tokens} is always set before a call.
 * A call with no price is not estimated: the gateway refuses it rather than guess.
 */
@Component
public class CostEstimator {

    private final PriceBook prices;

    public CostEstimator(PriceBook prices) {
        this.prices = prices;
    }

    public Optional<BigDecimal> llm(UUID tenantId, String vendor, String model, long inputTokens, long outputTokens,
            LocalDate date) {
        Optional<BigDecimal> in = prices.unitPrice(tenantId, vendor, model, PriceUnit.INPUT_TOKEN, date);
        Optional<BigDecimal> out = prices.unitPrice(tenantId, vendor, model, PriceUnit.OUTPUT_TOKEN, date);
        if (in.isEmpty() || out.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal fee = prices.unitPrice(tenantId, vendor, model, PriceUnit.REQUEST, date).orElse(BigDecimal.ZERO);
        return Optional.of(in.get().multiply(BigDecimal.valueOf(inputTokens))
                .add(out.get().multiply(BigDecimal.valueOf(outputTokens))).add(fee));
    }

    public Optional<BigDecimal> request(UUID tenantId, String vendor, String product, LocalDate date) {
        return prices.unitPrice(tenantId, vendor, product, PriceUnit.REQUEST, date);
    }

    /** How many output tokens the rest of a reservation can pay for after the input. */
    public Optional<Long> affordableOutput(UUID tenantId, String vendor, String model, long inputTokens,
            BigDecimal budget, LocalDate date) {
        Optional<BigDecimal> in = prices.unitPrice(tenantId, vendor, model, PriceUnit.INPUT_TOKEN, date);
        Optional<BigDecimal> out = prices.unitPrice(tenantId, vendor, model, PriceUnit.OUTPUT_TOKEN, date);
        if (in.isEmpty() || out.isEmpty() || out.get().signum() == 0) {
            return Optional.empty();
        }
        BigDecimal left = budget.subtract(in.get().multiply(BigDecimal.valueOf(inputTokens)));
        return Optional.of(left.signum() <= 0 ? 0L : left.divide(out.get(), 0, java.math.RoundingMode.FLOOR).longValue());
    }
}
