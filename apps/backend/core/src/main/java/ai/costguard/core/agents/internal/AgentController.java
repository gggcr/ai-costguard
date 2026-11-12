package ai.costguard.core.agents.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
@RequestMapping("/api/v1")
@Tag(name = "Agents", description = "AI agents, their virtual keys and mandates")
class AgentController {

    private final AgentService service;

    AgentController(AgentService service) {
        this.service = service;
    }

    private static UUID tenant(Authentication auth) {
        return TenantClaim.of((Jwt) auth.getPrincipal());
    }

    private static Actor actor(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        boolean privileged = roles.contains("ROLE_owner") || roles.contains("ROLE_it_admin");
        return new Actor(TenantClaim.of(jwt), !privileged, jwt.getSubject());
    }

    @GetMapping("/agents")
    @Operation(summary = "List agents")
    List<Agent> agents(Authentication auth) {
        return service.list(tenant(auth));
    }

    @PostMapping("/agents")
    @ManagesAgents
    @Operation(summary = "Connect an agent")
    ResponseEntity<Agent> create(Authentication auth, @Valid @RequestBody AgentDetails details) {
        Agent created = service.create(actor(auth), details);
        return ResponseEntity.created(URI.create("/api/v1/agents/" + created.id())).body(created);
    }

    @GetMapping("/agents/{id}")
    @Operation(summary = "Get an agent")
    Agent agent(Authentication auth, @PathVariable UUID id) {
        return service.get(tenant(auth), id);
    }

    @PutMapping("/agents/{id}")
    @ManagesAgents
    @Operation(summary = "Update an agent")
    Agent update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody AgentDetails details) {
        return service.update(actor(auth), id, details);
    }

    @DeleteMapping("/agents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesAgents
    @Operation(summary = "Delete an agent and its keys")
    void delete(Authentication auth, @PathVariable UUID id) {
        service.delete(actor(auth), id);
    }

    @PutMapping("/agents/{id}/suspension")
    @ManagesAgents
    @Operation(summary = "Stop an agent", description = "Idempotent.")
    Agent suspend(Authentication auth, @PathVariable UUID id) {
        return service.setStatus(actor(auth), id, AgentStatus.suspended);
    }

    @DeleteMapping("/agents/{id}/suspension")
    @ManagesAgents
    @Operation(summary = "Start a stopped agent", description = "Idempotent.")
    Agent resume(Authentication auth, @PathVariable UUID id) {
        return service.setStatus(actor(auth), id, AgentStatus.active);
    }

    @GetMapping("/agents/{id}/keys")
    @Operation(summary = "List an agent's keys, without secrets")
    List<VirtualKey> keys(Authentication auth, @PathVariable UUID id) {
        return service.keys(tenant(auth), id);
    }

    @PostMapping("/agents/{id}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @ManagesAgents
    @Operation(summary = "Issue a key", description = "The secret is in this response only.")
    IssuedKey issueKey(Authentication auth, @PathVariable UUID id) {
        return service.issueKey(actor(auth), id);
    }

    @DeleteMapping("/agents/{id}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesAgents
    @Operation(summary = "Revoke a key", description = "Idempotent.")
    void revokeKey(Authentication auth, @PathVariable UUID id, @PathVariable UUID keyId) {
        service.revokeKey(actor(auth), id, keyId);
    }

    @GetMapping("/mandates")
    @Operation(summary = "List mandates")
    List<Mandate> mandates(Authentication auth) {
        return service.mandates(tenant(auth));
    }

    @PostMapping("/mandates")
    @ManagesMandates
    @Operation(summary = "Create a draft mandate")
    ResponseEntity<Mandate> createMandate(Authentication auth, @Valid @RequestBody MandateDetails details) {
        Mandate created = service.createMandate(tenant(auth), details);
        return ResponseEntity.created(URI.create("/api/v1/mandates/" + created.id())).body(created);
    }

    @GetMapping("/mandates/{id}")
    @Operation(summary = "Get a mandate")
    Mandate mandate(Authentication auth, @PathVariable UUID id) {
        return service.getMandate(tenant(auth), id);
    }

    @PutMapping("/mandates/{id}")
    @ManagesMandates
    @Operation(summary = "Edit a mandate", description = "It becomes a draft until published again.")
    Mandate updateMandate(Authentication auth, @PathVariable UUID id, @Valid @RequestBody MandateDetails details) {
        return service.updateMandate(tenant(auth), id, details);
    }

    @PutMapping("/mandates/{id}/publication")
    @ManagesMandates
    @Operation(summary = "Publish a mandate to the gateway", description = "Idempotent.")
    Mandate publishMandate(Authentication auth, @PathVariable UUID id) {
        return service.publishMandate(tenant(auth), id);
    }

    @DeleteMapping("/mandates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesMandates
    @Operation(summary = "Delete a mandate that no agent uses")
    void deleteMandate(Authentication auth, @PathVariable UUID id) {
        service.deleteMandate(tenant(auth), id);
    }
}
