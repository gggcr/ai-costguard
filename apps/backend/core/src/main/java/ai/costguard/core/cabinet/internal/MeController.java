package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class MeController {

    /** Realm roles that mean something in CostGuard; Keycloak adds its own (offline_access and the like). */
    static final Set<String> ROLES = Set.of("owner", "finance", "it_admin", "agent_owner", "auditor", "employee");

    private final CabinetService service;

    MeController(CabinetService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Who I am: name, roles and the employee record of the token")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        return service.me(TenantClaim.of(jwt), jwt.getSubject(), jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"), roles(jwt));
    }

    static List<String> roles(Jwt jwt) {
        var access = jwt.getClaimAsMap("realm_access");
        if (access == null || !(access.get("roles") instanceof java.util.Collection<?> all)) {
            return List.of();
        }
        return all.stream().map(String::valueOf).filter(ROLES::contains).sorted().toList();
    }
}
