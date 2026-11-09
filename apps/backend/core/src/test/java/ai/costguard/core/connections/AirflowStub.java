package ai.costguard.core.connections;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The Airflow REST API as far as the core uses it: it records each POST and answers with a set status. */
final class AirflowStub {

    record Call(String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    private AirflowStub(HttpServer server) {
        this.server = server;
    }

    static AirflowStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AirflowStub stub = new AirflowStub(server);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                stub.calls.add(new Call(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(stub.status, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void answer(int status) {
        this.status = status;
    }

    int calls() {
        return calls.size();
    }

    Call last() {
        return calls.getLast();
    }
}
