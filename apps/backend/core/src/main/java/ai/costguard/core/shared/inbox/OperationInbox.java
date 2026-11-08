package ai.costguard.core.shared.inbox;

import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.journal.Receipts;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.JournalAnchor;
import ai.costguard.events.OperationDecision;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the core does with the gateway's operations. Every target is safe to call again with the same operation,
 * which is what at-least-once delivery needs.
 */
class OperationInbox {

    private static final Logger log = LoggerFactory.getLogger(OperationInbox.class);

    private final OperationPosting ledger;
    private final Receipts receipts;
    private final ApprovalRequests approvals;

    OperationInbox(OperationPosting ledger, Receipts receipts, ApprovalRequests approvals) {
        this.ledger = ledger;
        this.receipts = receipts;
        this.approvals = approvals;
    }

    void handle(AgentOperation op) {
        if (op.getDecision() == OperationDecision.PENDING_APPROVAL) {
            approvals.open(op.getTenantId(), op.getOperationId(), op.getAgentId(),
                    op.getKind() + " " + op.getProvider() + (op.getModel() == null ? "" : " " + op.getModel()),
                    op.getEstimatedCost(), op.getDenialCode() == null ? "above_threshold" : op.getDenialCode());
            return;
        }
        if (op.getDecision() != OperationDecision.ALLOWED || op.getActualCost() == null) {
            return;
        }
        BigDecimal cost = op.getActualCost();
        if (!ledger.charge(op.getTenantId(), op.getOperationId(), op.getAgentId(), cost, op.getOccurredAt())) {
            log.warn("Agent {} of tenant {} has no budget; operation {} is not in the ledger", op.getAgentId(),
                    op.getTenantId(), op.getOperationId());
        }
        receipts.issue(op.getTenantId(), op.getOperationId(), op.getAgentId(), cost, op.getCurrency(),
                op.getOccurredAt());
    }

    void handle(JournalAnchor anchor, java.util.UUID tenantId) {
        receipts.anchored(tenantId, anchor.getBatchId(), anchor.getMerkleRoot(), anchor.getReceiptIds(),
                anchor.getNearTxHash(), anchor.getAnchoredAt());
    }
}
