package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Бюджет-светофор агентов и подтверждение их операций (ADR 0067). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class AgentLightController {

    private final AgentLightService service;

    AgentLightController(AgentLightService service) {
        this.service = service;
    }

    @GetMapping("/me/agents")
    @Operation(summary = "My agents with their traffic light, operations waiting for me and my limit requests")
    MyAgents mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(TenantClaim.of(jwt), jwt);
    }

    @PostMapping("/me/agents/{agentId}/limit-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask to raise my agent's limit with a short reason; the head of my department is told")
    LimitRequest askLimit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID agentId,
            @Valid @RequestBody NewLimitRequest request) {
        return service.askLimit(TenantClaim.of(jwt), jwt, agentId, request);
    }

    @DeleteMapping("/me/limit-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel my limit request that waits for a decision")
    void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancel(TenantClaim.of(jwt), jwt, id);
    }

    @PutMapping("/me/agents/operations/{approvalId}/decision")
    @Operation(summary = "Confirm (NEAR keeps it) or block an operation of my agent that the gateway held back")
    OperationDecided decideOperation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID approvalId,
            @Valid @RequestBody OperationDecision decision) {
        return service.decideOperation(TenantClaim.of(jwt), jwt, approvalId, decision);
    }

    @GetMapping("/me/team/limit-requests")
    @Operation(summary = "Limit requests of the people of the departments I head")
    List<LimitRequest> team(@AuthenticationPrincipal Jwt jwt) {
        return service.team(TenantClaim.of(jwt), jwt);
    }

    @PutMapping("/me/team/limit-requests/{id}/decision")
    @Operation(summary = "Decide on a limit request of my department, as its head")
    LimitRequest decideAsHead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody LimitDecision decision) {
        return service.decideAsHead(TenantClaim.of(jwt), jwt, id, decision);
    }

    @GetMapping("/limit-requests")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Every limit request of the company")
    List<LimitRequest> all(@AuthenticationPrincipal Jwt jwt) {
        return service.all(TenantClaim.of(jwt));
    }

    @PutMapping("/limit-requests/{id}/decision")
    @PreAuthorize("hasAnyRole('owner', 'finance')")
    @Operation(summary = "Decide on a limit request as finance (a department without a head)")
    LimitRequest decideAsEmployer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody LimitDecision decision) {
        return service.decideAsEmployer(TenantClaim.of(jwt), id, decision, Whoami.who(jwt));
    }
}
