package ai.costguard.events.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;

/**
 * Registers a schema under {@code <topic>-value} and looks schemas up by id, caching both. Registering the same
 * schema again returns the same id, and the registry refuses an incompatible one, so a bad schema stops at the
 * producer and not at a consumer.
 */
public class SchemaRegistryClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final Map<Integer, Schema> schemas = new ConcurrentHashMap<>();

    public SchemaRegistryClient(URI base) {
        this.base = base;
    }

    public int idOf(String topic, Schema schema) {
        return ids.computeIfAbsent(topic + "|" + schema.getFullName() + "|" + schema.hashCode(),
                k -> register(topic, schema));
    }

    public Schema schemaOf(int id) {
        return schemas.computeIfAbsent(id, this::fetch);
    }

    private int register(String topic, Schema schema) {
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("schema", schema.toString()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpResponse<String> response = call(HttpRequest.newBuilder(base.resolve("/subjects/" + topic + "-value/versions"))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Schema Registry refused " + topic + ": " + response.statusCode() + " "
                    + response.body());
        }
        return read(response.body()).get("id").asInt();
    }

    private Schema fetch(int id) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(base.resolve("/schemas/ids/" + id)).GET());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Schema Registry has no schema " + id + ": " + response.statusCode());
        }
        return new Schema.Parser().parse(read(response.body()).get("schema").asText());
    }

    private HttpResponse<String> call(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Schema Registry is not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Schema Registry answered something that is not JSON", e);
        }
    }
}
