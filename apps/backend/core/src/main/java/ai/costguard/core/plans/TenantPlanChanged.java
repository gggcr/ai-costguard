package ai.costguard.core.plans;

import java.time.Instant;
import java.util.UUID;

/**
 * The plan as the gateway must know it, published on every change (tenants.plans.v1). {@code operationsPerMonth}
 * and {@code accessUntil} are null when there is no limit or no end. {@code version} grows with every change.
 */
public record TenantPlanChanged(UUID tenantId, String plan, boolean readOnly, Long operationsPerMonth,
        Instant accessUntil, long version, Instant at) {
}
