package ai.costguard.core.plans;

import java.util.UUID;

/** How a company gets its first plan, and loses it when its registration is abandoned. */
public interface PlanEnrollment {

    /** Starts the trial of a new company. Call inside the transaction that creates it, bound to the tenant. */
    void startTrial(UUID tenantId);

    /**
     * Deletes the plan of a company whose registration was never confirmed, and removes it from the gateway. Call
     * inside a transaction bound to the tenant.
     */
    void forget(UUID tenantId);
}
