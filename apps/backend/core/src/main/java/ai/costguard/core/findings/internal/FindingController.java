package ai.costguard.core.findings.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/findings")
@Tag(name = "Findings", description = "Findings of the audit engine and their life cycle")
class FindingController {

    private final FindingService service;

    FindingController(FindingService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List findings, optionally by status")
    List<Finding> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        return service.list(TenantClaim.of(jwt), status);
    }

    @GetMapping("/totals")
    @Operation(summary = "Open findings and savings, expected and confirmed")
    FindingTotals totals(@AuthenticationPrincipal Jwt jwt) {
        return service.totals(TenantClaim.of(jwt));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a finding with its history")
    Finding get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(TenantClaim.of(jwt), id);
    }

    @PostMapping("/{id}/actions")
    @WorksOnFindings
    @Operation(summary = "Move a finding along its life cycle", description = "Board frame 8.")
    Finding act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        String by = jwt.getClaimAsString("email");
        return service.act(TenantClaim.of(jwt), id, request, jwt.getSubject(), by == null ? jwt.getSubject() : by);
    }
}
