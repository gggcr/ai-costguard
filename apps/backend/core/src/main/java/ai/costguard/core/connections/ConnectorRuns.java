package ai.costguard.core.connections;

import java.time.Instant;
import java.util.UUID;

/** Pulls the connector agent reported, from {@code connectors.runs.v1}. */
public interface ConnectorRuns {

    record Run(UUID runId, UUID tenantId, UUID connectionId, String type, Instant startedAt, Instant finishedAt,
            boolean succeeded, int records, String error) {
    }

    /**
     * Records the pull. Safe to repeat. A pull of a system configured only in the agent's file creates its
     * connection, so the page shows every system the agent pulls.
     */
    void recorded(Run run);
}
