package ai.costguard.core.org.internal;

import ai.costguard.core.plans.TenantPlanChanged;
import ai.costguard.platform.tenant.TenantSession;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** The company card shows the plan; the plans module owns it and says when it changes (ADR 0037). */
@Component
class PlanFollower {

    private final OrgRepository org;
    private final TenantSession session;

    PlanFollower(OrgRepository org, TenantSession session) {
        this.org = org;
        this.session = session;
    }

    @ApplicationModuleListener
    void on(TenantPlanChanged e) {
        session.bind(e.tenantId());
        org.setPlan(e.plan());
    }
}
