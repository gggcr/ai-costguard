package ai.costguard.core.plans;

import java.time.Instant;
import java.util.UUID;

/** Published once per period, {@code costguard.plans.warn-before} before the trial or the paid period ends. */
public record PlanAccessEnding(UUID tenantId, String plan, boolean trial, Instant accessUntil) {
}
