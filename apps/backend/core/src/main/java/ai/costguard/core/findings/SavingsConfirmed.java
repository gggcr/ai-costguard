package ai.costguard.core.findings;

import java.math.BigDecimal;
import java.util.UUID;

/** The saving showed in an invoice. */
public record SavingsConfirmed(UUID tenantId, UUID findingId, String title, BigDecimal monthlySavings) {
}
