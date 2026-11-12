package ai.costguard.core.approvals.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Operations waiting for a human decision")
class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    private static UUID tenant(Authentication auth) {
        return TenantClaim.of((Jwt) auth.getPrincipal());
    }

    @GetMapping
    @Operation(summary = "List requests, optionally by status")
    List<Approval> list(Authentication auth, @RequestParam(required = false) ApprovalStatus status) {
        return service.list(tenant(auth), status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a request")
    Approval get(Authentication auth, @PathVariable UUID id) {
        return service.get(tenant(auth), id);
    }

    @PutMapping("/{id}/decision")
    @DecidesApprovals
    @Operation(summary = "Approve or reject", description = "A closed request answers 409.")
    Approval decide(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Decision decision) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        boolean restricted = !roles.contains("ROLE_owner") && !roles.contains("ROLE_finance");
        Decider decider = new Decider(TenantClaim.of(jwt), restricted, jwt.getSubject(),
                jwt.getClaimAsString("email"));
        return service.decide(decider, id, decision);
    }
}
