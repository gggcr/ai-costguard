package ai.costguard.core.org.internal;

import ai.costguard.core.org.Staff;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The same dismissal as {@code PUT /employees/{id}/dismissal}, started by an HR system instead of a person. */
@Component
class ServiceStaff implements Staff {

    private final OrgService service;

    ServiceStaff(OrgService service) {
        this.service = service;
    }

    @Override
    public void dismissedInHrSystem(UUID tenantId, UUID employeeId) {
        service.dismiss(tenantId, employeeId);
    }
}
