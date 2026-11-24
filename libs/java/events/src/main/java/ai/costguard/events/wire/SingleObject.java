package ai.costguard.events.wire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;

/**
 * Avro's single-object encoding: a 2-byte marker, the 8-byte fingerprint of the schema, then Avro binary. Used
 * between the core and the gateway when they run without Kafka and the Schema Registry (ADR 0080): both carry the
 * same generated classes, and a record written with another schema is refused instead of read wrong.
 */
public final class SingleObject {

    static {
        AvroWire.trustEventClasses();
    }

    private SingleObject() {
    }

    public static byte[] encode(SpecificRecord record) {
        try {
            ByteBuffer buffer = new BinaryMessageEncoder<SpecificRecord>(SpecificData.getForClass(record.getClass()),
                    record.getSchema()).encode(record);
            byte[] out = new byte[buffer.remaining()];
            buffer.get(out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T extends SpecificRecord> T decode(byte[] message, Class<T> type) {
        SpecificData model = SpecificData.getForClass(type);
        try {
            return new BinaryMessageDecoder<T>(model, model.getSchema(type)).decode(message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
