package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.core.journal.Receipts;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The metrics the alerts of deploy/helm/costguard/templates/monitoring.yaml read (ADR 0026). */
@SpringBootTest
class OperationsMetricsIntegrationTest extends PostgresTestSupport {

    @Autowired MeterRegistry registry;
    @Autowired ConnectorRuns runs;
    @Autowired Receipts receipts;

    private double counter(String name, String... tags) {
        var search = registry.find(name).tags(tags);
        return search.counter() == null ? 0 : search.counter().count();
    }

    @Test
    void connectorRunsAreCountedByTypeAndOutcomeOnce() {
        UUID tenant = UUID.randomUUID();
        double failedBefore = counter("costguard.connector.runs", "type", "okta", "status", "FAILED");
        var run = new ConnectorRuns.Run(UUID.randomUUID(), tenant, null, "okta", Instant.now().minusSeconds(5),
                Instant.now(), false, 0, "401");
        runs.recorded(run);
        runs.recorded(run);
        runs.recorded(new ConnectorRuns.Run(UUID.randomUUID(), tenant, null, "okta", Instant.now().minusSeconds(5),
                Instant.now(), true, 3, null));
        assertThat(counter("costguard.connector.runs", "type", "okta", "status", "FAILED")).isEqualTo(failedBefore + 1);
        assertThat(counter("costguard.connector.runs", "type", "okta", "status", "SUCCEEDED")).isPositive();
    }

    @Test
    void receiptsAndTheOutboxAreMeasured() {
        double before = counter("costguard.journal.receipts.issued");
        receipts.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "RUB", Instant.now());
        assertThat(counter("costguard.journal.receipts.issued")).isEqualTo(before + 1);
        assertThat(registry.get("costguard.journal.last.anchor.timestamp.seconds").gauge()).isNotNull();
        assertThat(registry.get("costguard.outbox.pending.events").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(registry.get("costguard.outbox.oldest.pending.seconds").gauge().value())
                .isGreaterThanOrEqualTo(0);
    }
}
