package ai.costguard.core.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A rate the core loaded: the price of one unit of {@code currency} from {@code date} in the base of the configured
 * source, roubles (Bank of Russia) or euros (ECB, ADR 0075), the base itself worth one; the field keeps its old
 * name. The gateway converts between two currencies of one base, so the base cancels out. The gateway
 * prices a call in the company's currency with it (ADR 0072). Rates are the same for every company.
 */
public record ExchangeRateChanged(String currency, BigDecimal roubles, LocalDate date) {
}
