package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An operation was charged for the first time: it becomes a cost record. */
public record OperationCharged(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, String currency,
        Instant occurredAt) {
}
