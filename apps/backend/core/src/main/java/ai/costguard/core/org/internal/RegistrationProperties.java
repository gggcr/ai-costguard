package ai.costguard.core.org.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code costguard.registration} (ADR 0032).
 *
 * @param linkLifespan how long the link to confirm the e-mail and set a password works
 * @param unconfirmedFor how long an unconfirmed registration is kept before the sweeper drops it
 * @param sweepDelay how often the sweeper runs
 */
@ConfigurationProperties("costguard.registration")
record RegistrationProperties(
        @DefaultValue Keycloak keycloak,
        @DefaultValue("PT24H") Duration linkLifespan,
        @DefaultValue("PT48H") Duration unconfirmedFor,
        @DefaultValue("PT15M") Duration sweepDelay,
        @DefaultValue RateLimit rateLimit) {

    /** The admin API client that creates accounts. Off while {@code clientSecret} is empty. */
    record Keycloak(
            @DefaultValue("http://localhost:8180") String url,
            @DefaultValue("costguard") String realm,
            @DefaultValue("costguard-registration") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("costguard-frontend") String loginClientId,
            @DefaultValue("http://localhost:3000") String redirectUri,
            @DefaultValue("PT10S") Duration timeout) {

        boolean configured() {
            return clientSecret != null && !clientSecret.isBlank();
        }
    }

    /** At most {@code perIp} attempts from one address and {@code perEmail} for one e-mail within {@code window}. */
    record RateLimit(
            @DefaultValue("5") int perIp,
            @DefaultValue("3") int perEmail,
            @DefaultValue("PT1H") Duration window) {
    }
}
