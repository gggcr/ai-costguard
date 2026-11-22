package ai.costguard.core.org;

import java.util.UUID;

/** A company named another time zone (ADR 0073): whatever the gateway checks by the hour hears it again. */
public record CompanyTimeZoneChanged(UUID tenantId, String timeZone) {
}
