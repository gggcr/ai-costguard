package ai.costguard.core.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Converts an amount between two currencies at the Bank of Russia rates of the latest day on or before {@code date}.
 * The bank quotes roubles for one unit of each currency, so a pair without the rouble goes through it: a company
 * that keeps its money in dollars converts euros to dollars (ADR 0072). RUR, the old code of the rouble, is the
 * rouble. Empty when either currency has no rate by then.
 */
public interface ExchangeRates {

    Optional<BigDecimal> convert(BigDecimal amount, String from, String to, LocalDate date);
}
