package ai.costguard.core.findings;

import java.math.BigDecimal;
import java.util.UUID;

/** Where the audit engine's findings arrive. Both calls are safe to repeat. */
public interface FindingsIntake {

    /** Creates the finding, refreshes it, or reopens it if it had closed by itself. Returns its id. */
    UUID detected(UUID tenantId, String fingerprint, String rule, String title, String description,
            BigDecimal expectedSavings, UUID subscriptionId, UUID agentId);

    /** The condition is gone: an open finding closes by itself. */
    void resolved(UUID tenantId, String fingerprint);
}
