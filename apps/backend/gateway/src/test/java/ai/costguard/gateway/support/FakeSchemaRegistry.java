package ai.costguard.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Registers schemas and serves them by id: the two calls the wire format needs. */
public final class FakeSchemaRegistry implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final Map<Integer, String> schemas = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    public FakeSchemaRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/subjects/", this::register);
        server.createContext("/schemas/ids/", this::fetch);
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void register(HttpExchange exchange) throws IOException {
        String schema = JSON.readTree(exchange.getRequestBody()).get("schema").asText();
        int id = ids.computeIfAbsent(schema, s -> {
            int n = next.getAndIncrement();
            schemas.put(n, s);
            return n;
        });
        answer(exchange, 200, JSON.writeValueAsString(Map.of("id", id)));
    }

    private void fetch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String schema = schemas.get(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)));
        if (schema == null) {
            answer(exchange, 404, "{}");
        } else {
            answer(exchange, 200, JSON.writeValueAsString(Map.of("schema", schema)));
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
