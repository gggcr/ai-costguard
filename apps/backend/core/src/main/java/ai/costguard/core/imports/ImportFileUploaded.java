package ai.costguard.core.imports;

import java.time.Instant;
import java.util.UUID;

/**
 * For the ingest pipeline: where the file is and how to recognise it. {@code country} and {@code currency} are the
 * company's (ADR 0072): how its people write numbers and dates, and the currency of an amount the file names none
 * for; null when the company has not said.
 */
public record ImportFileUploaded(UUID fileId, UUID tenantId, String storageKey, String sha256, String fileName,
        String contentType, UUID vendorId, Instant uploadedAt, String country, String currency) {
}
