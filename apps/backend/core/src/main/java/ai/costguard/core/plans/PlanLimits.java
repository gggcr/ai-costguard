package ai.costguard.core.plans;

import java.util.UUID;
import java.util.function.LongSupplier;

/** The limits of the company's plan, for the modules that create what a plan limits. */
public interface PlanLimits {

    /**
     * Fails with 402 {@code plan_limit_exceeded} when the company already has as many as its plan allows, or with
     * 402 {@code plan_read_only} when its trial or paid period ended. Call inside the transaction that creates the
     * new item, already bound to the tenant: the plan row stays locked until it commits, so two requests at once
     * cannot both take the last place. {@code used} counts the module's own rows and is called under that lock.
     * A company without a plan (it existed before plans) is not limited.
     */
    void requireRoom(UUID tenantId, Limit limit, LongSupplier used);
}
