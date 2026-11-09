package ai.costguard.core.connections.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Connections", description = "Systems the connector agent pulls and how each pull went")
class ConnectionController {

    private final ConnectionService service;

    ConnectionController(ConnectionService service) {
        this.service = service;
    }

    private static UUID tenant(Authentication auth) {
        return TenantClaim.of((Jwt) auth.getPrincipal());
    }

    @GetMapping
    @Operation(operationId = "listConnections", summary = "List connected systems",
            description = "Every system the company connected, and every system its agent reported pulling.")
    List<Connection> list(Authentication auth) {
        return service.list(tenant(auth));
    }

    @GetMapping("/types")
    @Operation(operationId = "listConnectionTypes", summary = "Systems that can be connected",
            description = "Each with the settings it needs and the secrets the agent must have in its environment.")
    List<SystemTypes.SystemType> types() {
        return service.types();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getConnection", summary = "Get a connected system")
    Connection get(Authentication auth, @PathVariable UUID id) {
        return service.get(tenant(auth), id);
    }

    @GetMapping("/{id}/runs")
    @Operation(operationId = "listConnectionRuns", summary = "The last 20 pulls of a connected system")
    List<ConnectionRun> runs(Authentication auth, @PathVariable UUID id) {
        return service.runs(tenant(auth), id);
    }

    @PostMapping
    @ManagesConnections
    @Operation(operationId = "connectSystem", summary = "Connect a system",
            description = "Settings, and optionally secrets: kept encrypted in the core and never returned, or left out "
                    + "for the agent's environment (ADR 0030). Connecting a disconnected system again reuses it.")
    ResponseEntity<Connection> connect(Authentication auth, @Valid @RequestBody ConnectionDetails details) {
        ConnectionService.Connected result = service.connect(tenant(auth), details);
        Connection c = result.connection();
        return result.created() ? ResponseEntity.created(URI.create("/api/v1/connections/" + c.id())).body(c)
                : ResponseEntity.ok(c);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ManagesConnections
    @Operation(operationId = "disconnectSystem", summary = "Disconnect a system",
            description = "The agent stops pulling it and secrets kept in the core are erased; the history stays.")
    void disconnect(Authentication auth, @PathVariable UUID id) {
        service.disconnect(tenant(auth), id);
    }

    @PostMapping("/{id}/sync")
    @ManagesConnections
    @Operation(operationId = "syncConnection", summary = "Sync a system now",
            description = "A running agent pulls it at once; an agent on Airflow at once if the core knows Airflow, "
                    + "otherwise on its next hourly round.")
    ResponseEntity<Connection> sync(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.sync(tenant(auth), id));
    }

    @GetMapping("/agent-secrets")
    @RunsConnectors
    @Operation(operationId = "connectorAgentSecrets", summary = "Secrets for the connector agent",
            description = "The secrets kept in the core, by system type and environment variable. Only for the "
                    + "connector service account of the company (ADR 0030).")
    AgentSecrets agentSecrets(Authentication auth) {
        return service.agentSecrets(tenant(auth));
    }
}
