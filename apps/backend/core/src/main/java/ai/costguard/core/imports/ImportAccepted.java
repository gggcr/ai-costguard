package ai.costguard.core.imports;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** People confirmed the mapping and resolved every review task: the pipeline may write the rows. */
public record ImportAccepted(UUID tenantId, UUID fileId, String storageKey, String fileName, UUID vendorId,
        Map<String, String> mapping, Map<Integer, Map<String, String>> corrections, Instant acceptedAt) {
}
