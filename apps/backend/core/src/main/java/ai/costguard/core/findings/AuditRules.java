package ai.costguard.core.findings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rules of the audit engine. Each module checks the data it owns and returns what it found; the engine turns that
 * into findings and closes the findings of the same rules that were not found again.
 */
public interface AuditRules {

    /** The rules this provider decides; only their findings are closed when they are not found. */
    Set<String> rules();

    /** Called inside a transaction already bound to the tenant. */
    List<Detection> detect(UUID tenantId, LocalDate today);

    /**
     * @param fingerprint the same condition gives the same fingerprint on every run
     * @param expectedSavings per month, in the currency of the company
     */
    record Detection(String rule, String fingerprint, String title, String description, BigDecimal expectedSavings,
            UUID subscriptionId) {
    }
}
