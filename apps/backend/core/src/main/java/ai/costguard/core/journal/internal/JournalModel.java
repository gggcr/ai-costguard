package ai.costguard.core.journal.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum ReceiptStatus { pending, anchored }

record StoredReceipt(UUID id, UUID operationId, long seq, String payload, String hash, String previousHash,
        String signature, String keyId, Instant issuedAt, UUID batchId) {
}

/** What an auditor needs to check a receipt without access to the database. */
record ReceiptView(UUID id, UUID operationId, String payload, String hash, String previousHash, String signature,
        String keyId, ReceiptStatus status, UUID batchId, String merkleRoot, String txHash, List<String> merklePath) {
}

record ChainVerification(boolean valid, long receipts, Long brokenAtSeq, String problem) {
}

record SigningKey(String keyId, String algorithm, String publicKey) {
}

record Batch(UUID id, String merkleRoot, String txHash) {
}
