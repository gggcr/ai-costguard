package ai.costguard.gateway.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Redis counts in millionths of the currency unit, as integers, so Lua never rounds a float. */
public final class Money {

    private Money() {
    }

    public static long micros(BigDecimal amount) {
        return amount.setScale(6, RoundingMode.CEILING).unscaledValue().longValueExact();
    }

    public static BigDecimal of(long micros) {
        return BigDecimal.valueOf(micros, 6).stripTrailingZeros();
    }
}
