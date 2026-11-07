package ai.costguard.core.imports;

import java.time.Instant;
import java.util.UUID;

/** For the ingest pipeline: where the file is and how to recognise it. */
public record ImportFileUploaded(UUID fileId, UUID tenantId, String storageKey, String sha256, String fileName,
        String contentType, UUID vendorId, Instant uploadedAt) {
}
