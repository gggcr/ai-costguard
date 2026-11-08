package ai.costguard.core.approvals;

import java.time.Instant;
import java.util.UUID;

/** What the NEAR notary reports back about the large operations it confirmed. */
public interface OperationConfirmations {

    /**
     * Records the transaction that confirmed the operation. Safe to repeat. A hash that is not the one the core
     * computed is refused with an error and changes nothing.
     */
    void confirmed(UUID tenantId, UUID operationId, String operationHash, String nearTxHash, Instant confirmedAt);
}
