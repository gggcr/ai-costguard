package ai.costguard.core.approvals.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Each request is handled in its own transaction, so one bad request cannot stop the rest. Running this on several
 * instances is safe: the row lock and the status check make the second one do nothing.
 */
@Component
class ApprovalTimer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimer.class);

    private final ApprovalService service;

    ApprovalTimer(ApprovalService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${costguard.approvals.timer-delay:PT30S}")
    void tick() {
        for (DueApproval due : service.dueForExpiry()) {
            try {
                service.expire(due);
            } catch (RuntimeException e) {
                log.error("Could not expire approval {}", due.approvalId(), e);
            }
        }
        for (DueApproval due : service.dueForEscalation()) {
            try {
                service.escalate(due);
            } catch (RuntimeException e) {
                log.error("Could not escalate approval {}", due.approvalId(), e);
            }
        }
    }
}
