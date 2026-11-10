package ai.costguard.gateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.AgentRiskSignal;
import ai.costguard.events.AgentState;
import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.events.TenantPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecordBase;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Kafka contracts of the gateway against schemas/avro: what it reads (state from the core and the detector) and
 * what it writes (agent.operations.v1). A listener added without an entry here fails the test. Needs no Docker.
 */
class TopicContractTest {

    static final Map<String, Class<? extends SpecificRecordBase>> CONSUMED = Map.of(
            "agents.state.v1", AgentState.class,
            "policy.snapshots.v1", PolicySnapshot.class,
            "catalog.prices.v1", CatalogPrice.class,
            "budgets.limits.v1", BudgetLimit.class,
            "agent.risk-signals.v1", AgentRiskSignal.class,
            "approvals.decisions.v1", ApprovalDecision.class,
            "tenants.plans.v1", TenantPlan.class);

    private static Schema file(String topic) throws Exception {
        Path path = Path.of(System.getProperty("repo.root", "../../.."), "schemas", "avro", topic + ".avsc");
        return new Schema.Parser().parse(Files.readString(path));
    }

    /** The class's schema without what only the Java generator (-string) adds to every string. */
    private static Schema generated(Class<? extends SpecificRecordBase> type) throws Exception {
        Schema schema = (Schema) type.getField("SCHEMA$").get(null);
        return new Schema.Parser().parse(schema.toString().replace(",\"avro.java.string\":\"String\"", ""));
    }

    @Test
    void everyListenerHasAContract() {
        Set<String> listened = Arrays.stream(StateListeners.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(KafkaListener.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(KafkaListener.class).topics()))
                .collect(Collectors.toSet());
        assertThat(listened).isEqualTo(CONSUMED.keySet());
    }

    @Test
    void everyConsumedTopicIsReadWithItsOwnSchema() throws Exception {
        for (var entry : CONSUMED.entrySet()) {
            assertThat(generated(entry.getValue())).as(entry.getKey()).isEqualTo(file(entry.getKey()));
        }
    }

    @Test
    void operationsAreWrittenWithTheirSchema() throws Exception {
        assertThat(OperationLog.TOPIC).isEqualTo("agent.operations.v1");
        assertThat(generated(AgentOperation.class)).isEqualTo(file(OperationLog.TOPIC));
    }
}
