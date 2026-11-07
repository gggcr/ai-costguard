package ai.costguard.core.policies.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "Company rules over agent operations, simulation and publication")
class PolicyController {

    private final PolicyService service;

    PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @Operation(summary = "List rules")
    List<PolicyRule> rules(@AuthenticationPrincipal Jwt jwt) {
        return service.rules(TenantClaim.of(jwt));
    }

    @PostMapping("/rules")
    @ManagesPolicies
    @Operation(summary = "Add a rule", description = "The gateway sees it only after publication.")
    ResponseEntity<PolicyRule> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RuleDetails details) {
        PolicyRule created = service.create(TenantClaim.of(jwt), details);
        return ResponseEntity.created(URI.create("/api/v1/policies/rules/" + created.id())).body(created);
    }

    @PutMapping("/rules/{id}")
    @ManagesPolicies
    @Operation(summary = "Change a rule")
    PolicyRule update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RuleDetails details) {
        return service.update(TenantClaim.of(jwt), id, details);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesPolicies
    @Operation(summary = "Delete a rule")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(TenantClaim.of(jwt), id);
    }

    @GetMapping("/publication")
    @Operation(summary = "The version the gateway has", description = "404 before the first publication.")
    ResponseEntity<Publication> publication(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(service.publication(TenantClaim.of(jwt)));
    }

    @PostMapping("/publication")
    @ManagesPolicies
    @Operation(summary = "Publish the enabled rules to the gateway")
    Publication publish(@AuthenticationPrincipal Jwt jwt) {
        return service.publish(TenantClaim.of(jwt));
    }

    @PostMapping("/simulation")
    @Operation(summary = "Try rules on sample operations", description = "Changes nothing.")
    SimulationResult simulate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SimulationRequest request) {
        return service.simulate(TenantClaim.of(jwt), request);
    }
}
