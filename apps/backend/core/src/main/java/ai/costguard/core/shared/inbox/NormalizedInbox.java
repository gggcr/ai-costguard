package ai.costguard.core.shared.inbox;

import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.events.IngestNormalized;
import ai.costguard.platform.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands vendors, people and licenses from files and connectors to the imports module. A payload that is not JSON
 * would fail on every retry, so it is logged and skipped; anything else (the database is down) is thrown, and the
 * message comes again.
 */
class NormalizedInbox {

    private static final Logger log = LoggerFactory.getLogger(NormalizedInbox.class);

    private final NormalizedEntities entities;

    NormalizedInbox(NormalizedEntities entities) {
        this.entities = entities;
    }

    void handle(IngestNormalized e) {
        try {
            entities.received(e.getTenantId(), e.getFileId(), e.getEntityKey(), e.getEntityType().name(),
                    e.getPayload(), e.getConfidence(), e.getNormalizedAt());
        } catch (IllegalArgumentException | ApiException ex) {
            log.warn("Entity {} of tenant {} skipped: {}", e.getEntityKey(), e.getTenantId(), ex.getMessage());
        }
    }
}
