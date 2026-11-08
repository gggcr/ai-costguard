package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.shared.TopicSchemas;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.ConnectorRun;
import ai.costguard.events.Finding;
import ai.costguard.events.IngestNormalized;
import ai.costguard.events.IngestResult;
import ai.costguard.events.JournalAnchor;
import ai.costguard.events.NotaryConfirmation;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecordBase;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * The consumer contract of the core: every topic it listens to is decoded into the class generated from that
 * topic's schema in schemas/avro. A listener added without an entry here fails the test.
 */
class TopicContractTest {

    static final Map<String, Class<? extends SpecificRecordBase>> CONSUMED = Map.of(
            "agent.operations.v1", AgentOperation.class,
            "ingest.results.v1", IngestResult.class,
            "ingest.normalized.v1", IngestNormalized.class,
            "findings.v1", Finding.class,
            "journal.anchors.v1", JournalAnchor.class,
            "notary.confirmations.v1", NotaryConfirmation.class,
            "connectors.runs.v1", ConnectorRun.class);

    @Test
    void everyListenerHasAContract() {
        Set<String> listened = Arrays.stream(InboxConfig.Listener.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(KafkaListener.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(KafkaListener.class).topics()))
                .collect(Collectors.toSet());
        assertThat(listened).isEqualTo(CONSUMED.keySet());
    }

    @Test
    void everyConsumedTopicIsReadWithItsOwnSchema() throws Exception {
        for (var entry : CONSUMED.entrySet()) {
            Schema generated = (Schema) entry.getValue().getField("SCHEMA$").get(null);
            assertThat(TopicSchemas.contract(generated)).as(entry.getKey())
                    .isEqualTo(TopicSchemas.of(entry.getKey()));
        }
    }
}
