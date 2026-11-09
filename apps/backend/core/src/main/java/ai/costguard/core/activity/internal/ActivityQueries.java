package ai.costguard.core.activity.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ActivityQueries {

    private final ActivityRepository repository;
    private final TenantSession session;

    ActivityQueries(ActivityRepository repository, TenantSession session) {
        this.repository = repository;
        this.session = session;
    }

    @Transactional(readOnly = true)
    List<ActivityEntry> entries(UUID tenantId, String action, UUID targetId, UUID actorId, Instant before, int limit) {
        if (limit < 1 || limit > 200) {
            throw ActivityProblem.BAD_LIMIT.failure();
        }
        session.bind(tenantId);
        return repository.entries(action, targetId, actorId, before, limit);
    }
}
