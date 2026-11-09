package ai.costguard.core.privacy.internal;

import ai.costguard.core.PostgresTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keycloak's admin API and ClickHouse's HTTP interface, faked in one small server, so the erasures talk to them the
 * way they do in production. The tests that extend it share it, and so share one Spring context. ClickHouse answers
 * every SELECT with {@link #CLICKHOUSE_ROWS} and records every other statement in {@link #CLICKHOUSE}.
 */
public abstract class PrivacyTestSupport extends PostgresTestSupport {

    public record Account(String id, String email, UUID tenantId) {
    }

    public static final Map<String, Account> ACCOUNTS = new ConcurrentHashMap<>();
    public static final List<String> CLICKHOUSE = new CopyOnWriteArrayList<>();
    public static final java.util.concurrent.atomic.AtomicReference<String> CLICKHOUSE_ROWS =
            new java.util.concurrent.atomic.AtomicReference<>("");
    public static volatile boolean keycloakDown;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer SERVER = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/realms/costguard/protocol/openid-connect/token",
                    x -> reply(x, keycloakDown ? 503 : 200, "{\"access_token\":\"t\"}"));
            server.createContext("/admin/realms/costguard/users", PrivacyTestSupport::users);
            server.createContext("/", x -> {
                String sql = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (sql.stripLeading().startsWith("SELECT")) {
                    reply(x, 200, CLICKHOUSE_ROWS.get());
                    return;
                }
                CLICKHOUSE.add(sql);
                reply(x, 200, "");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                out.put(kv[0], kv.length < 2 ? "" : URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void users(HttpExchange x) throws IOException {
        if (!"Bearer t".equals(x.getRequestHeaders().getFirst("Authorization"))) {
            reply(x, 401, "");
            return;
        }
        String path = x.getRequestURI().getPath();
        if ("DELETE".equals(x.getRequestMethod())) {
            String id = path.substring(path.lastIndexOf('/') + 1);
            reply(x, ACCOUNTS.remove(id) == null ? 404 : 204, "");
            return;
        }
        Map<String, String> q = query(x);
        List<Map<String, Object>> found = new ArrayList<>();
        for (Account a : ACCOUNTS.values()) {
            boolean match = q.containsKey("email") ? a.email().equalsIgnoreCase(q.get("email"))
                    : q.getOrDefault("q", "").equals("tenant_id:" + a.tenantId());
            if (match) {
                found.add(Map.of("id", a.id(), "email", a.email(),
                        "attributes", Map.of("tenant_id", List.of(a.tenantId().toString()))));
            }
        }
        reply(x, 200, JSON.writeValueAsString(found));
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            x.getResponseBody().write(bytes);
        }
        x.close();
    }

    @DynamicPropertySource
    static void outsideSystems(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("costguard.privacy.keycloak.url", () -> url);
        registry.add("costguard.privacy.keycloak.client-secret", () -> "s");
        registry.add("costguard.clickhouse.url", () -> url);
        // The job runs when a test calls it, not on its own schedule.
        registry.add("costguard.privacy.retention.cron", () -> "-");
    }
}
