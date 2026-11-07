package ai.costguard.core.approvals;

import java.math.BigDecimal;
import java.util.UUID;

/** Opens a request for an operation the gateway held back. */
public interface ApprovalRequests {

    /** Safe to call again for the same operation: it returns the request that is already there. */
    UUID open(UUID tenantId, UUID operationId, UUID agentId, String description, BigDecimal amount, String reason);
}
