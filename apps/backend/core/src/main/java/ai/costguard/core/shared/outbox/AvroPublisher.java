package ai.costguard.core.shared.outbox;

import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;

/** Encodes a record in the Schema Registry wire format and hands it to the sender. */
class AvroPublisher {

    /** Sends synchronously: it returns only when Kafka has the message, so the outbox can mark it done. */
    interface Sender {
        void send(String topic, String key, byte[] value);
    }

    private final SchemaRegistryClient registry;
    private final Sender sender;

    AvroPublisher(SchemaRegistryClient registry, Sender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    void publish(String topic, String key, SpecificRecord record) {
        sender.send(topic, key, AvroWire.encode(registry.idOf(topic, record.getSchema()), record));
    }

    /** A null value: compaction then drops every record of the key (ADR 0021). */
    void tombstone(String topic, String key) {
        sender.send(topic, key, null);
    }

    static byte[] encode(int schemaId, SpecificRecord record) {
        return AvroWire.encode(schemaId, record);
    }
}
