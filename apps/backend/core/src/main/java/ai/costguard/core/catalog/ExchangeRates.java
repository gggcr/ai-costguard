package ai.costguard.core.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Converts to roubles at the Bank of Russia rate of the latest day on or before {@code date}. */
public interface ExchangeRates {

    Optional<BigDecimal> toRub(BigDecimal amount, String currency, LocalDate date);
}
