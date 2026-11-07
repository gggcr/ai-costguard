package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Entry point for settled operations. Safe to call again with the same operation. */
public interface OperationPosting {

    /** Charges the agent's budget and every budget above it. Returns false if the agent has no budget. */
    boolean charge(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, Instant occurredAt);

    /** Cancels an earlier charge on every budget it touched. */
    void reverse(UUID tenantId, UUID operationId, Instant occurredAt);
}
