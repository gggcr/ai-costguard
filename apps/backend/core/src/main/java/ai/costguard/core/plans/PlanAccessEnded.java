package ai.costguard.core.plans;

import java.time.Instant;
import java.util.UUID;

/** Published once per period, when the trial or the paid period ended and the company became read-only. */
public record PlanAccessEnded(UUID tenantId, String plan, boolean trial, Instant endedAt) {
}
