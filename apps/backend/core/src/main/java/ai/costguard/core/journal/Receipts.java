package ai.costguard.core.journal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Receipts {

    /** Issues the receipt of an allowed operation. Safe to repeat: it returns the receipt already issued. */
    UUID issue(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, String currency, Instant occurredAt);

    /** Records that the notary anchored these receipts in NEAR. Safe to repeat. */
    void anchored(UUID tenantId, UUID batchId, String merkleRoot, List<UUID> receiptIds, String nearTxHash,
            Instant anchoredAt);
}
