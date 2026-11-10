package ai.costguard.gateway.plans;

import ai.costguard.events.TenantPlan;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Plans of the companies as {@code tenants.plans.v1} describes them (ADR 0037). A company with no record here
 * existed before plans and is not limited; the core publishes a record for every company it gives a plan.
 */
@Component
public class PlanBoard {

    private final Map<UUID, TenantPlan> plans = new ConcurrentHashMap<>();

    /** An older version (a redelivered message) never replaces a newer one. */
    public void apply(TenantPlan plan) {
        plans.merge(plan.getTenantId(), plan, (old, now) -> now.getVersion() >= old.getVersion() ? now : old);
    }

    /** A tombstone: the company was erased or its registration dropped. */
    public void remove(UUID tenantId) {
        plans.remove(tenantId);
    }

    public Optional<TenantPlan> of(UUID tenantId) {
        return tenantId == null ? Optional.empty() : Optional.ofNullable(plans.get(tenantId));
    }
}
