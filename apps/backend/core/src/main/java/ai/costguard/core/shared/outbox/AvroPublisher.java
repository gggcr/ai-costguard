package ai.costguard.core.shared.outbox;

import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;

/**
 * Encodes a record and hands it to the sender: in the Schema Registry wire format for Kafka, or in Avro's
 * single-object encoding for the gateway feed of installs without Kafka (ADR 0080).
 */
class AvroPublisher {

    /** Sends synchronously: it returns only when Kafka has the message, so the outbox can mark it done. */
    interface Sender {
        void send(String topic, String key, byte[] value);
    }

    interface Encoder {
        byte[] encode(String topic, SpecificRecord record);
    }

    private final Encoder encoder;
    private final Sender sender;

    AvroPublisher(SchemaRegistryClient registry, Sender sender) {
        this((topic, record) -> AvroWire.encode(registry.idOf(topic, record.getSchema()), record), sender);
    }

    AvroPublisher(Encoder encoder, Sender sender) {
        this.encoder = encoder;
        this.sender = sender;
    }

    void publish(String topic, String key, SpecificRecord record) {
        sender.send(topic, key, encoder.encode(topic, record));
    }

    /** A null value: compaction then drops every record of the key (ADR 0021). */
    void tombstone(String topic, String key) {
        sender.send(topic, key, null);
    }

    static byte[] encode(int schemaId, SpecificRecord record) {
        return AvroWire.encode(schemaId, record);
    }
}
