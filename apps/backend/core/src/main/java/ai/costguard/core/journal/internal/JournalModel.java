package ai.costguard.core.journal.internal;

import java.math.BigDecimal;
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

/** {@code unverifiedSignatures}: receipts signed with a key this instance does not know, so not checked. */
record ChainVerification(boolean valid, long receipts, Long brokenAtSeq, String problem, long unverifiedSignatures) {
}

record SigningKey(String keyId, String algorithm, String publicKey) {
}

record Batch(UUID id, String merkleRoot, String txHash) {
}

/**
 * One decision of the gateway as the web client shows it. {@code cost} is what was charged, null unless allowed;
 * {@code receipt} is null for operations without a receipt (denied ones, or not yet received by the core).
 */
record OperationView(UUID id, UUID agentId, Instant at, String kind, String provider, String model, String decision,
        String denialCode, BigDecimal estimatedCost, BigDecimal cost, String currency, Long inputTokens,
        Long outputTokens, ReceiptStatus receipt) {

    OperationView withReceipt(ReceiptStatus status) {
        return new OperationView(id, agentId, at, kind, provider, model, decision, denialCode, estimatedCost, cost,
                currency, inputTokens, outputTokens, status);
    }
}
