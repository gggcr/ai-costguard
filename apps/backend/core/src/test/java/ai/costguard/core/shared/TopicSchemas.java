package ai.costguard.core.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;

/** The contract of a topic as schemas/avro holds it: the file, not the class generated from it. */
public final class TopicSchemas {

    private TopicSchemas() {
    }

    /**
     * A schema without what only Java adds: the generator (-string) marks every string "avro.java.string". The
     * structure, names, logical types and defaults are compared as they are.
     */
    public static Schema contract(Schema schema) {
        return new Schema.Parser().parse(schema.toString().replace(",\"avro.java.string\":\"String\"", ""));
    }

    public static Schema of(String topic) {
        Path file = Path.of(System.getProperty("repo.root", "../../.."), "schemas", "avro", topic + ".avsc");
        try {
            return new Schema.Parser().parse(Files.readString(file));
        } catch (IOException e) {
            throw new AssertionError("No schema for topic " + topic + " at " + file.toAbsolutePath(), e);
        }
    }
}
