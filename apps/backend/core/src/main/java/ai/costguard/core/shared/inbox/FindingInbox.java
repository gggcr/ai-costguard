package ai.costguard.core.shared.inbox;

import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.events.Finding;
import ai.costguard.platform.errors.ApiException;
import java.math.RoundingMode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Findings of services outside the core, such as the anomaly detector, from {@code findings.v1}. An entity id
 * {@code agent:<uuid>} or {@code subscription:<uuid>} ties the finding to that agent or subscription. The topic
 * has no description, so the title is used for both.
 */
class FindingInbox {

    private static final Logger log = LoggerFactory.getLogger(FindingInbox.class);

    private final FindingsIntake intake;

    FindingInbox(FindingsIntake intake) {
        this.intake = intake;
    }

    void handle(Finding f) {
        UUID agent = entity(f, "agent:");
        UUID subscription = entity(f, "subscription:");
        try {
            intake.detected(f.getTenantId(), f.getFingerprint(), f.getRule(), f.getTitle(), f.getTitle(),
                    f.getExpectedSavings().setScale(2, RoundingMode.HALF_EVEN).max(java.math.BigDecimal.ZERO),
                    subscription, agent);
        } catch (ApiException | IllegalArgumentException e) {
            log.warn("Finding {} of tenant {} skipped: {}", f.getFingerprint(), f.getTenantId(), e.getMessage());
        }
    }

    private static UUID entity(Finding f, String prefix) {
        for (String id : f.getEntityIds()) {
            if (id.startsWith(prefix)) {
                try {
                    return UUID.fromString(id.substring(prefix.length()));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
