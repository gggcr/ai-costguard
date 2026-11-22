package ai.costguard.core.connections.internal;

import ai.costguard.core.connections.ConnectionChanged;
import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.plans.Limit;
import ai.costguard.core.plans.PlanLimits;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConnectionService implements ConnectorRuns {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int MAX_ERROR = 2000;

    private final ConnectionRepository connections;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final AirflowLauncher airflow;
    private final SecretBox box;
    private final PlanLimits plans;

    private final EmployeeDirectory company;

    ConnectionService(ConnectionRepository connections, TenantSession session, Clock clock,
            ApplicationEventPublisher events, MeterRegistry registry, AirflowLauncher airflow, SecretBox box,
            PlanLimits plans, EmployeeDirectory company) {
        this.company = company;
        this.plans = plans;
        this.box = box;
        this.connections = connections;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.registry = registry;
        this.airflow = airflow;
    }

    /** Pulls by system type and outcome, for the alert on failing connectors (ADR 0026). No tenant: one label. */
    private void count(String type, boolean succeeded) {
        Counter.builder("costguard.connector.runs").description("Pulls the connector agent reported")
                .tag("type", SystemTypes.of(type).isPresent() ? type : "other")
                .tag("status", succeeded ? "SUCCEEDED" : "FAILED").register(registry).increment();
    }

    @Transactional(readOnly = true)
    List<Connection> list(UUID tenantId) {
        session.bind(tenantId);
        return connections.list();
    }

    @Transactional(readOnly = true)
    Connection get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return found(id);
    }

    @Transactional(readOnly = true)
    List<ConnectionRun> runs(UUID tenantId, UUID id) {
        session.bind(tenantId);
        found(id);
        return connections.runs(id, 20);
    }

    private Connection found(UUID id) {
        return connections.find(id).orElseThrow(ConnectionProblem.CONNECTION_NOT_FOUND::failure);
    }

    /** Only the settings the type knows, required ones present: a secret has no name to come in under. */
    private static Map<String, String> checked(ConnectionDetails details) {
        SystemTypes.SystemType type = SystemTypes.of(details.type())
                .orElseThrow(ConnectionProblem.UNKNOWN_SYSTEM_TYPE::failure);
        Map<String, String> settings = new TreeMap<>();
        details.settings().forEach((key, value) -> {
            if (!type.required().contains(key) && !type.optional().contains(key)) {
                throw ConnectionProblem.SETTING_UNKNOWN.failure();
            }
            if (value != null && !value.isBlank()) {
                settings.put(key, value.strip());
            }
        });
        if (!settings.keySet().containsAll(type.required())) {
            throw ConnectionProblem.SETTING_MISSING.failure();
        }
        return settings;
    }

    /** Only the secrets the type names, blank ones left out (ADR 0030). */
    private static Map<String, String> secrets(ConnectionDetails details) {
        Map<String, String> secrets = new TreeMap<>();
        if (details.secrets() == null) {
            return secrets;
        }
        List<String> known = SystemTypes.of(details.type()).orElseThrow().secrets();
        details.secrets().forEach((key, value) -> {
            if (!known.contains(key)) {
                throw ConnectionProblem.SECRET_UNKNOWN.failure();
            }
            if (value != null && !value.isBlank()) {
                secrets.put(key, value);
            }
        });
        return secrets;
    }

    /** Connects a system, or connects again one that was disconnected. Returns whether it is new. */
    @Transactional
    Connected connect(UUID tenantId, ConnectionDetails details) {
        Map<String, String> settings = checked(details);
        Map<String, String> secrets = secrets(details);
        session.bind(tenantId);
        Instant now = clock.instant();
        var existing = connections.lockByType(details.type());
        if (existing.isEmpty() || !connections.enabled(existing.get())) {
            // A system that is connected already takes no new place of the plan (ADR 0037).
            plans.requireRoom(tenantId, Limit.CONNECTED_SYSTEMS, connections::countEnabled);
        }
        UUID id;
        if (existing.isPresent()) {
            id = existing.get();
            if (connections.enabled(id) && found(id).managedInCore()) {
                throw ConnectionProblem.ALREADY_CONNECTED.failure();
            }
            connections.reconnect(id, details.name().strip(), settings, now);
        } else {
            id = UUID.randomUUID();
            connections.insert(tenantId, id, details.type(), details.name().strip(), true, settings, now);
        }
        connections.setSecrets(id, secrets.isEmpty() ? null : box.seal(tenantId, id, secrets),
                List.copyOf(secrets.keySet()));
        publish(tenantId, id, now);
        // The names of the settings and secrets, not their values: a value can be an e-mail or a password.
        events.publishEvent(ActionTaken.of(tenantId, "connection.connected", "connection", id, Map.of(
                "type", details.type(), "settings", String.join(",", settings.keySet()),
                "secrets", String.join(",", secrets.keySet()))));
        return new Connected(found(id), existing.isEmpty());
    }

    record Connected(Connection connection, boolean created) {
    }

    /** The agent stops pulling the system; its history stays. Disconnecting twice changes nothing. */
    @Transactional
    void disconnect(UUID tenantId, UUID id) {
        session.bind(tenantId);
        connections.lock(id).orElseThrow(ConnectionProblem.CONNECTION_NOT_FOUND::failure);
        if (!connections.enabled(id)) {
            return;
        }
        Instant now = clock.instant();
        connections.disable(id, now);
        publish(tenantId, id, now);
        events.publishEvent(ActionTaken.of(tenantId, "connection.disconnected", "connection", id,
                Map.of("type", found(id).type())));
    }

    /**
     * Asks the agent to pull the system now. An agent that runs all the time sees it in connectors.config.v1; for the
     * agent in Airflow the core starts the DAG costguard_connector_sync, if Airflow is configured.
     */
    @Transactional
    Connection sync(UUID tenantId, UUID id) {
        session.bind(tenantId);
        connections.lock(id).orElseThrow(ConnectionProblem.CONNECTION_NOT_FOUND::failure);
        if (!connections.enabled(id)) {
            throw ConnectionProblem.NOT_CONNECTED.failure();
        }
        Instant now = clock.instant();
        connections.requestSync(id, now);
        publish(tenantId, id, now);
        String type = found(id).type();
        events.publishEvent(ActionTaken.of(tenantId, "connection.sync_requested", "connection", id,
                Map.of("type", type)));
        airflow.launch(tenantId, id, type, now); // the agent in Airflow pulls now, not on its next hourly round
        return found(id);
    }

    /** The secrets kept in the core, opened, for the connector agent's service account only (ADR 0030). */
    @Transactional(readOnly = true)
    AgentSecrets agentSecrets(UUID tenantId) {
        session.bind(tenantId);
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (ConnectionRepository.Sealed s : connections.sealed()) {
            out.put(s.type(), box.open(tenantId, s.id(), s.secrets()));
        }
        return new AgentSecrets(tenantId, out);
    }

    private void publish(UUID tenantId, UUID id, Instant now) {
        Connection c = found(id);
        String currency = company.company().map(EmployeeDirectory.CompanyCard::currency).orElse(null);
        events.publishEvent(new ConnectionChanged(tenantId, c.id(), c.type(), c.name(), c.enabled(), c.settings(),
                c.syncRequestedAt(), now, currency));
    }

    @Override
    @Transactional
    public void recorded(Run run) {
        session.bind(run.tenantId());
        UUID id = run.connectionId() != null ? connections.lock(run.connectionId()).orElse(null) : null;
        if (id == null) {
            id = connections.lockByType(run.type()).orElse(null);
        }
        if (id == null) {
            if (SystemTypes.of(run.type()).isEmpty()) {
                log.warn("Run {} of tenant {} is of an unknown system type {}; skipped", run.runId(), run.tenantId(),
                        run.type());
                return;
            }
            id = UUID.randomUUID();
            connections.insert(run.tenantId(), id, run.type(), SystemTypes.of(run.type()).orElseThrow().title(),
                    false, Map.of(), run.finishedAt());
        }
        String error = run.error() == null ? null
                : run.error().length() > MAX_ERROR ? run.error().substring(0, MAX_ERROR) : run.error();
        if (connections.insertRun(run.tenantId(), id, run.runId(), run.startedAt(), run.finishedAt(),
                run.succeeded(), Math.max(0, run.records()), error)) {
            count(run.type(), run.succeeded());
        }
    }

    List<SystemTypes.SystemType> types() {
        return List.copyOf(SystemTypes.ALL.values());
    }
}
