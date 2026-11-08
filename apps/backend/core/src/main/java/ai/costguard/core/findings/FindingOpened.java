package ai.costguard.core.findings;

import java.math.BigDecimal;
import java.util.UUID;

/** A new finding, or one that came back. */
public record FindingOpened(UUID tenantId, UUID findingId, String title, BigDecimal expectedSavings) {
}
