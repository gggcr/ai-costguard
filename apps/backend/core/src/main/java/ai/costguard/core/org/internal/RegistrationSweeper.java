package ai.costguard.core.org.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops registrations nobody confirmed in time, and the rate-limit attempts past their window (ADR 0032). */
@Component
class RegistrationSweeper {

    private static final Logger log = LoggerFactory.getLogger(RegistrationSweeper.class);

    private final RegistrationService registrations;
    private final RegistrationAttempts attempts;
    private final IdentityProvider accounts;

    RegistrationSweeper(RegistrationService registrations, RegistrationAttempts attempts, IdentityProvider accounts) {
        this.registrations = registrations;
        this.attempts = attempts;
        this.accounts = accounts;
    }

    @Scheduled(fixedDelayString = "${costguard.registration.sweep-delay:PT15M}", initialDelayString = "PT1M")
    void run() {
        attempts.forgetOld();
        if (!accounts.configured()) {
            return;
        }
        for (UUID tenantId : registrations.expired()) {
            try {
                registrations.sweep(tenantId);
            } catch (RuntimeException e) {
                // Keycloak may be down: the registration stays due and is tried on the next round.
                log.warn("Sweeping registration of tenant {} failed: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
