package ai.costguard.core.imports;

import java.time.Instant;
import java.util.UUID;

/** Entities from ingest.normalized.v1: uploaded files and connectors report vendors, people, licenses and costs. */
public interface NormalizedEntities {

    /**
     * Stores the entity under its key and ties it to a vendor, employee or license of the core when it can. A
     * report older than the stored one changes nothing, so a replayed message is harmless.
     *
     * @return the vendor, employee or license the entity was matched to, or null when people have to look at it
     */
    UUID received(UUID tenantId, UUID sourceId, String entityKey, String entityType, String payload,
            double confidence, Instant at);
}
