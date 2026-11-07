package ai.costguard.core.journal;

import java.time.Instant;
import java.util.UUID;

/** For the NEAR notary. */
public record ReceiptIssued(UUID receiptId, UUID tenantId, UUID operationId, String hash, String previousHash,
        String signature, String signingKeyId, Instant issuedAt) {
}
