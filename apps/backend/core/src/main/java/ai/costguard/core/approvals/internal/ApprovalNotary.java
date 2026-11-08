package ai.costguard.core.approvals.internal;

import ai.costguard.core.approvals.OperationConfirmations;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records the NEAR transactions of confirmed operations, from {@code notary.confirmations.v1}. */
@Service
class ApprovalNotary implements OperationConfirmations {

    private final ApprovalRepository approvals;
    private final TenantSession session;

    ApprovalNotary(ApprovalRepository approvals, TenantSession session) {
        this.approvals = approvals;
        this.session = session;
    }

    @Override
    @Transactional
    public void confirmed(UUID tenantId, UUID operationId, String operationHash, String nearTxHash,
            Instant confirmedAt) {
        session.bind(tenantId);
        String expected = approvals.notaryHashOfOperation(operationId)
                .orElseThrow(ApprovalProblem.APPROVAL_NOT_FOUND::failure);
        if (!expected.equals(operationHash)) {
            throw ApprovalProblem.NOTARY_HASH_MISMATCH.failure();
        }
        approvals.notarized(operationId, nearTxHash, confirmedAt);
    }
}
