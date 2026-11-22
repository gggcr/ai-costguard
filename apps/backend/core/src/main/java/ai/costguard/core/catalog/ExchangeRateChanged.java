package ai.costguard.core.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A Bank of Russia rate the core loaded: roubles for one unit of {@code currency} from {@code date}. The gateway
 * prices a call in the company's currency with it (ADR 0072). Rates are the same for every company.
 */
public record ExchangeRateChanged(String currency, BigDecimal roubles, LocalDate date) {
}
