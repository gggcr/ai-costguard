package ai.costguard.core.findings.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class FindingTimer {

    private static final Logger log = LoggerFactory.getLogger(FindingTimer.class);

    private final FindingService service;

    FindingTimer(FindingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${costguard.findings.timer-delay:PT10M}")
    void tick() {
        for (DueFinding due : service.dueWakeups()) {
            try {
                service.wake(due);
            } catch (RuntimeException e) {
                log.error("Could not wake finding {}", due.findingId(), e);
            }
        }
    }
}
