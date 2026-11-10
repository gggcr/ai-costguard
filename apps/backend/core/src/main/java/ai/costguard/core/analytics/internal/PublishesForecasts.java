package ai.costguard.core.analytics.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The forecast job's service account (a Keycloak client with the forecast role and tenant_id), and people who may
 * publish a forecast by hand.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('owner', 'finance', 'forecast')")
@interface PublishesForecasts {
}
