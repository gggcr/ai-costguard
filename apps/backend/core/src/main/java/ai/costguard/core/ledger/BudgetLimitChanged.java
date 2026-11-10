package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The full limit of a budget, for the gateway. {@code version} grows with every change of the limit. A snapshot of
 * the ledger (ADR 0040) carries the same version with {@code ledgerSpent}: what the postings charged up to
 * {@code ledgerAsOf}; a change of the limit carries neither.
 */
public record BudgetLimitChanged(UUID tenantId, UUID budgetId, UUID parentBudgetId, String level, UUID refId,
        BigDecimal limitAmount, String currency, long version, BigDecimal ledgerSpent, Instant ledgerAsOf) {

    public BudgetLimitChanged(UUID tenantId, UUID budgetId, UUID parentBudgetId, String level, UUID refId,
            BigDecimal limitAmount, String currency, long version) {
        this(tenantId, budgetId, parentBudgetId, level, refId, limitAmount, currency, version, null, null);
    }
}
