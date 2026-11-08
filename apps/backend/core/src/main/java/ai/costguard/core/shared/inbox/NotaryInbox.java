package ai.costguard.core.shared.inbox;

import ai.costguard.core.agents.MandateNotarizations;
import ai.costguard.core.approvals.OperationConfirmations;
import ai.costguard.events.NotarizedKind;
import ai.costguard.events.NotaryConfirmation;
import ai.costguard.platform.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the NEAR notary wrote, from {@code notary.confirmations.v1}: a mandate version goes to agents, a large
 * operation to approvals. A hash that does not match the core's own is refused there and logged here: retrying it
 * would never succeed, and it may mean a forged confirmation.
 */
class NotaryInbox {

    private static final Logger log = LoggerFactory.getLogger(NotaryInbox.class);

    private final MandateNotarizations mandates;
    private final OperationConfirmations operations;

    NotaryInbox(MandateNotarizations mandates, OperationConfirmations operations) {
        this.mandates = mandates;
        this.operations = operations;
    }

    void handle(NotaryConfirmation c) {
        try {
            if (c.getKind() == NotarizedKind.MANDATE) {
                if (c.getMandateVersion() == null) {
                    log.warn("Mandate confirmation {} has no version and is skipped", c.getRequestId());
                    return;
                }
                mandates.notarized(c.getTenantId(), c.getSubjectId(), c.getMandateVersion(), c.getSubjectHash(),
                        c.getNearTxHash(), c.getConfirmedAt());
            } else {
                operations.confirmed(c.getTenantId(), c.getSubjectId(), c.getSubjectHash(), c.getNearTxHash(),
                        c.getConfirmedAt());
            }
        } catch (ApiException e) {
            log.warn("Notary confirmation {} of tenant {} refused: {}", c.getRequestId(), c.getTenantId(),
                    e.getMessage());
        }
    }
}
