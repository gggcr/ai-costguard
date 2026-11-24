package ai.costguard.gateway.events;

import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every instance reads every state topic from the start, with a group of its own, so each one holds the full
 * state in memory. The topics are compacted, so that read stays short. A listener per topic, so a slow topic does
 * not hold up the others. What a record does is in {@link StateApplier}; without Kafka
 * ({@code EVENTS_TRANSPORT=direct}) {@link CoreFeed} brings the same records.
 */
@Component
@ConditionalOnProperty(name = "costguard.gateway.transport", havingValue = "kafka", matchIfMissing = true)
public class StateListeners {

    private static final String GROUP = "gateway-#{T(java.util.UUID).randomUUID()}";

    private final SchemaRegistryClient registry;
    private final StateApplier state;

    public StateListeners(SchemaRegistryClient registry, StateApplier state) {
        this.registry = registry;
        this.state = state;
    }

    private void apply(ConsumerRecord<String, byte[]> r) {
        state.apply(r.topic(), r.key(), r.value(), type -> AvroWire.decode(r.value(), registry, type));
    }

    @KafkaListener(topics = "agents.state.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void agents(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "policy.snapshots.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void policies(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "catalog.prices.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void prices(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "catalog.rates.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void rates(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "budgets.limits.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void budgets(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "agent.risk-signals.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void risk(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "approvals.decisions.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void approvals(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }

    @KafkaListener(topics = "tenants.plans.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void plans(ConsumerRecord<String, byte[]> r) {
        apply(r);
    }
}
