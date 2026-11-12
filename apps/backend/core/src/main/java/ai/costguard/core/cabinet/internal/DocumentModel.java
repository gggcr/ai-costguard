package ai.costguard.core.cabinet.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

enum DocumentKind { employment_contract, agreement, order, other }

/** Who signs a document (ADR 0068): the employee, the employer (role owner) and the accountant (role finance). */
enum SignerParty { employee, employer, accountant }

/**
 * A document of one employee. {@code source} is upload for a file the employer added and asset_act for a handover
 * act of the assets module; {@code returnedOn} is set on an act whose device came back.
 */
record EmployeeDocument(UUID id, String source, String kind, String title, String number, LocalDate signedOn,
        String fileName, Long size, Instant createdAt, LocalDate returnedOn, List<String> signers,
        List<DocumentSignature> signatures) {
}

/** {@code signers}: the sides that must sign it; empty when nobody signs it in CostGuard. */
record StoredDocument(UUID id, UUID employeeId, String kind, String title, String number, LocalDate signedOn,
        String fileKey, String fileName, String contentType, long size, String uploadedBy, Instant createdAt,
        List<String> signers) {
}

/** One simple electronic signature: {@code documentSha256} is the hash of the original file that was signed. */
record DocumentSignature(String party, String signerName, String signerEmail, String method, String documentSha256,
        Instant signedAt) {
}

/** A document waiting for the employer's or the accountant's signature. */
record AwaitingSignature(UUID id, UUID employeeId, String employeeName, String title, String party, Instant createdAt,
        List<String> signers, List<DocumentSignature> signatures) {
}

record SignRequest(@jakarta.validation.constraints.NotBlank String code) {
}

/** {@code method}: totp, pin or null when not set up; {@code totpAvailable}: the server can keep an app secret. */
record SigningKeyStatus(String method, boolean totpAvailable, Instant lockedUntil) {
}

/** {@code uri}: the otpauth link the QR code carries; {@code secret}: the same key to type in by hand. */
record TotpSetup(String secret, String uri) {
}

record TotpStart(String current) {
}

record TotpConfirm(@jakarta.validation.constraints.NotBlank String code) {
}

record PinSetup(@jakarta.validation.constraints.NotBlank String pin, String current) {
}

record DocumentFile(String fileName, String contentType, byte[] content) {
}
