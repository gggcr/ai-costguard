package ai.costguard.core.approvals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Half of the time passed and nobody decided: notifications should reach further. */
public record ApprovalEscalated(UUID tenantId, UUID approvalId, UUID agentId, BigDecimal amount, Instant expiresAt) {
}
