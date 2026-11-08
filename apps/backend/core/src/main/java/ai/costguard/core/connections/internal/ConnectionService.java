package ai.costguard.core.connections.internal;

import ai.costguard.core.connections.ConnectionChanged;
import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.platform.tenant.TenantSession;
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

    ConnectionService(ConnectionRepository connections, TenantSession session, Clock clock,
            ApplicationEventPublisher events) {
        this.connections = connections;
        this.session = session;
        this.clock = clock;
        this.events = events;
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

    /** Connects a system, or connects again one that was disconnected. Returns whether it is new. */
    @Transactional
    Connected connect(UUID tenantId, ConnectionDetails details) {
        Map<String, String> settings = checked(details);
        session.bind(tenantId);
        Instant now = clock.instant();
        var existing = connections.lockByType(details.type());
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
        publish(tenantId, id, now);
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
    }

    /** Asks the agent to pull the system now. An agent on a schedule pulls it on its next round. */
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
        return found(id);
    }

    private void publish(UUID tenantId, UUID id, Instant now) {
        Connection c = found(id);
        events.publishEvent(new ConnectionChanged(tenantId, c.id(), c.type(), c.name(), c.enabled(), c.settings(),
                c.syncRequestedAt(), now));
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
        connections.insertRun(run.tenantId(), id, run.runId(), run.startedAt(), run.finishedAt(), run.succeeded(),
                Math.max(0, run.records()), error);
    }

    List<SystemTypes.SystemType> types() {
        return List.copyOf(SystemTypes.ALL.values());
    }
}
