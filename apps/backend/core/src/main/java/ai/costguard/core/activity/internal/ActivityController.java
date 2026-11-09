package ai.costguard.core.activity.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity")
@Tag(name = "Activity log", description = "Who changed what: budgets, mandates, roles, connections, equipment, erasures")
class ActivityController {

    private final ActivityQueries queries;

    ActivityController(ActivityQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @ReadsActivity
    @Operation(operationId = "listActivity", summary = "The activity log, newest first",
            description = "Every filter is optional. For the next page pass the time of the last entry as before.")
    List<ActivityEntry> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.entries(TenantClaim.of(jwt), action, targetId, actorId, before, limit);
    }
}
