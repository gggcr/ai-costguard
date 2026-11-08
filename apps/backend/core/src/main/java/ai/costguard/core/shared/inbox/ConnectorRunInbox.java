package ai.costguard.core.shared.inbox;

import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.events.ConnectorRun;
import ai.costguard.events.ConnectorRunStatus;

/** Pulls the connector agent reported, from {@code connectors.runs.v1}, for the page of connected systems. */
class ConnectorRunInbox {

    private final ConnectorRuns runs;

    ConnectorRunInbox(ConnectorRuns runs) {
        this.runs = runs;
    }

    void handle(ConnectorRun r) {
        runs.recorded(new ConnectorRuns.Run(r.getRunId(), r.getTenantId(), r.getConnectionId(), r.getType(),
                r.getStartedAt(), r.getFinishedAt(), r.getStatus() == ConnectorRunStatus.SUCCEEDED, r.getRecords(),
                r.getError()));
    }
}
