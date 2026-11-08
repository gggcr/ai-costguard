package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** The full limit of a budget, for the gateway. {@code version} grows with every change. */
public record BudgetLimitChanged(UUID tenantId, UUID budgetId, UUID parentBudgetId, String level, UUID refId,
        BigDecimal limitAmount, String currency, long version) {
}
