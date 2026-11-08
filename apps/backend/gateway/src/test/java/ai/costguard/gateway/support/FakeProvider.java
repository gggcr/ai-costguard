package ai.costguard.gateway.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for a provider API: records what it received and answers with a fixed body. */
public final class FakeProvider implements AutoCloseable {

    public record Received(String path, Map<String, List<String>> headers, String body) {
    }

    private final HttpServer server;
    public final List<Received> received = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile String contentType = "application/json";
    public volatile String response = """
            {"id":"x","choices":[{"message":{"role":"assistant","content":"ok"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":50}}""";

    public FakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
