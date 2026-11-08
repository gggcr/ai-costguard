package ai.costguard.core.approvals;

import java.time.Instant;
import java.util.UUID;

/**
 * A person approved an operation of at least {@code costguard.notary.confirm-above}. The NEAR notary confirms it by
 * {@code operationHash}; the approver is only {@code approverHash}. Both are sha256 hex (ADR 0009).
 */
public record LargeOperationApproved(UUID tenantId, UUID approvalId, UUID operationId, String operationHash,
        String approverHash, Instant approvedAt) {
}
