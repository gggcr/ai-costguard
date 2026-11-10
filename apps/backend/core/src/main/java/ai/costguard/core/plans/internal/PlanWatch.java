package ai.costguard.core.plans.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Warns before a trial or a paid period ends, and makes the company read-only when it ends (ADR 0037). */
@Component
class PlanWatch {

    private static final Logger log = LoggerFactory.getLogger(PlanWatch.class);

    private final PlanService plans;

    PlanWatch(PlanService plans) {
        this.plans = plans;
    }

    @Scheduled(fixedDelayString = "${costguard.plans.watch-delay:PT15M}", initialDelayString = "PT1M")
    void run() {
        for (UUID tenantId : plans.due()) {
            try {
                plans.watch(tenantId);
            } catch (RuntimeException e) {
                // One company's failure must not stop the others; it is due again on the next round.
                log.warn("Plan watch of tenant {} failed: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
