package ai.costguard.core.findings;

import java.math.BigDecimal;

/** Call inside a transaction already bound to the tenant. */
public interface FindingsSummary {

    Totals totals();

    record Totals(int open, BigDecimal expectedSavings, BigDecimal confirmedSavings) {
    }
}
