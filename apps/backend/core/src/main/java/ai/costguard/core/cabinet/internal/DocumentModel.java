package ai.costguard.core.cabinet.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

enum DocumentKind { employment_contract, agreement, order, other }

/**
 * A document of one employee. {@code source} is upload for a file the employer added and asset_act for a handover
 * act of the assets module; {@code returnedOn} is set on an act whose device came back.
 */
record EmployeeDocument(UUID id, String source, String kind, String title, String number, LocalDate signedOn,
        String fileName, Long size, Instant createdAt, LocalDate returnedOn) {
}

record StoredDocument(UUID id, UUID employeeId, String kind, String title, String number, LocalDate signedOn,
        String fileKey, String fileName, String contentType, long size, String uploadedBy, Instant createdAt) {
}

record DocumentFile(String fileName, String contentType, byte[] content) {
}
