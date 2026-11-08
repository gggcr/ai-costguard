package ai.costguard.core.org;

import java.util.UUID;

/** How connectors to HR systems change people. Joins the caller's transaction. */
public interface Staff {

    /**
     * The HR system says the employee left: dismisses them today and publishes {@link EmployeeDismissed} once.
     * An employee already dismissed is left as is.
     */
    void dismissedInHrSystem(UUID tenantId, UUID employeeId);
}
