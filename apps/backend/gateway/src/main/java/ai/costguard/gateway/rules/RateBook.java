package ai.costguard.gateway.rules;

import ai.costguard.events.ExchangeRate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The latest Bank of Russia rate of every currency, from {@code catalog.rates.v1} (ADR 0072): roubles for one unit.
 * A price in one currency becomes a price in another through the rouble. An older rate never replaces a newer one,
 * whatever order the records come in.
 */
@Component
public class RateBook {

    /** Enough digits for a dollar price of one token, $0.00000015, to survive the way through the rouble. */
    private static final MathContext DIGITS = new MathContext(24, RoundingMode.HALF_EVEN);

    private final Map<String, ExchangeRate> rates = new ConcurrentHashMap<>();

    public void apply(ExchangeRate rate) {
        if (rate.getRoubles() == null || rate.getRoubles().signum() <= 0) {
            return;
        }
        rates.merge(rate.getCurrency().strip(), rate,
                (old, next) -> next.getRateDate().isBefore(old.getRateDate()) ? old : next);
    }

    /** {@code amount} of {@code from} in {@code to}; empty when either currency has no rate here. */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        String source = rouble(from);
        String target = rouble(to);
        if (source.equals(target)) {
            return Optional.of(amount);
        }
        Optional<BigDecimal> perSource = roubles(source);
        Optional<BigDecimal> perTarget = roubles(target);
        if (perSource.isEmpty() || perTarget.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(amount.multiply(perSource.get()).divide(perTarget.get(), DIGITS));
    }

    private static String rouble(String currency) {
        String code = currency == null ? "" : currency.strip();
        return code.equals("RUR") ? "RUB" : code;
    }

    private Optional<BigDecimal> roubles(String currency) {
        return currency.equals("RUB") ? Optional.of(BigDecimal.ONE)
                : Optional.ofNullable(rates.get(currency)).map(ExchangeRate::getRoubles);
    }
}
