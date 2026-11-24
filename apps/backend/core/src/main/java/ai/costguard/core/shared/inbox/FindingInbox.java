package ai.costguard.core.shared.inbox;

import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.shared.names.DetectorText;
import ai.costguard.core.shared.names.Language;
import java.util.function.Function;
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
    private final Function<UUID, Language> languages;

    FindingInbox(FindingsIntake intake) {
        this(intake, tenant -> Language.RU);
    }

    /** The detector writes titles in Russian; they are kept in the company's language (ADR 0074, DetectorText). */
    FindingInbox(FindingsIntake intake, Function<UUID, Language> languages) {
        this.intake = intake;
        this.languages = languages;
    }

    void handle(Finding f) {
        UUID agent = entity(f, "agent:");
        UUID subscription = entity(f, "subscription:");
        String title = DetectorText.in(languages.apply(f.getTenantId()), f.getTitle());
        try {
            intake.detected(f.getTenantId(), f.getFingerprint(), f.getRule(), title, title,
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
