package ai.costguard.core.approvals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A request was closed. {@code status} is approved, rejected or expired. */
public record ApprovalDecided(UUID tenantId, UUID approvalId, UUID operationId, UUID agentId, String status,
        BigDecimal amount, Instant decidedAt) {
}
