package ai.costguard.gateway.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);

    private final BudgetReserve reserve;

    ReservationSweeper(BudgetReserve reserve) {
        this.reserve = reserve;
    }

    @Scheduled(fixedDelayString = "${costguard.gateway.sweep-delay:PT1M}")
    void sweep() {
        try {
            Long released = reserve.sweep().block();
            if (released != null && released > 0) {
                log.info("Released {} reservations past their hold", released);
            }
        } catch (RuntimeException e) {
            log.warn("Could not sweep reservations: {}", e.toString());
        }
    }
}
