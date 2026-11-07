package ai.costguard.core.shared.inbox;

import ai.costguard.core.journal.Receipts;
import ai.costguard.events.JournalAnchor;
import ai.costguard.platform.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batches the NEAR notary anchored, from {@code journal.anchors.v1}. Anchors written before the topic had a
 * tenant id cannot be bound to RLS and are skipped. A root that does not match the receipts is refused by the
 * journal and logged: retrying it would never succeed.
 */
class AnchorInbox {

    private static final Logger log = LoggerFactory.getLogger(AnchorInbox.class);

    private final Receipts receipts;

    AnchorInbox(Receipts receipts) {
        this.receipts = receipts;
    }

    void handle(JournalAnchor a) {
        if (a.getTenantId() == null) {
            log.warn("Anchor {} has no tenant id and is skipped", a.getBatchId());
            return;
        }
        try {
            receipts.anchored(a.getTenantId(), a.getBatchId(), a.getMerkleRoot(), a.getReceiptIds(),
                    a.getNearTxHash(), a.getAnchoredAt());
        } catch (ApiException e) {
            log.warn("Anchor {} of tenant {} refused: {}", a.getBatchId(), a.getTenantId(), e.getMessage());
        }
    }
}
