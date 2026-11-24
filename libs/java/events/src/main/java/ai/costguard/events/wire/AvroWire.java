package ai.costguard.events.wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;

/** The Schema Registry wire format: byte 0, a 4-byte big-endian schema id, then Avro binary. */
public final class AvroWire {

    /** Avro 1.12 refuses classes it does not know; trust exactly the generated event classes, nothing wider. */
    static {
        ClassSecurityValidator.ClassSecurityPredicate global = ClassSecurityValidator.getGlobal();
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                global, type -> type.getPackageName().equals("ai.costguard.events")));
    }

    private AvroWire() {
    }

    /** Runs the static block above: {@link SingleObject} decodes the same classes. */
    static void trustEventClasses() {
    }

    public static byte[] encode(int schemaId, SpecificRecord record) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(0);
            out.write(ByteBuffer.allocate(4).putInt(schemaId).array());
            BinaryEncoder encoder = EncoderFactory.get().directBinaryEncoder(out, null);
            new SpecificDatumWriter<SpecificRecord>(record.getSchema()).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int schemaId(byte[] message) {
        if (message.length < 5 || message[0] != 0) {
            throw new IllegalArgumentException("Not in the Schema Registry wire format");
        }
        return ByteBuffer.wrap(message, 1, 4).getInt();
    }

    /** Reads with the writer's schema and resolves it to {@code type}, so a compatible older writer still works. */
    public static <T extends SpecificRecord> T decode(byte[] message, Schema writer, Class<T> type) {
        Schema reader = SpecificData.get().getSchema(type);
        try {
            return new SpecificDatumReader<T>(writer, reader)
                    .read(null, DecoderFactory.get().binaryDecoder(message, 5, message.length - 5, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T extends SpecificRecord> T decode(byte[] message, SchemaRegistryClient registry, Class<T> type) {
        return decode(message, registry.schemaOf(schemaId(message)), type);
    }
}
