package ai.costguard.core.shared.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The analytics store over its HTTP interface. ClickHouse fills itself from Kafka (ADR 0007); the core only reads
 * from it to reconcile budgets (ADR 0026) and deletes a company's rows when the company is erased (ADR 0021). Off
 * while {@code costguard.clickhouse.url} is empty, as it is in tests and on a laptop without the stack.
 *
 * <p>Callers build SQL from UUIDs, numbers and configured names only, never from a request.
 */
@Component
public class ClickHouse {

    /** ClickHouse did not answer as expected. The message says which statement, not what it carried. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String what) {
            super(what);
        }
    }

    private final String url;
    private final String database;
    private final String user;
    private final String password;
    private final Duration timeout;
    private final HttpClient http;

    public ClickHouse(@Value("${costguard.clickhouse.url:}") String url,
            @Value("${costguard.clickhouse.database:costguard}") String database,
            @Value("${costguard.clickhouse.user:costguard}") String user,
            @Value("${costguard.clickhouse.password:}") String password,
            @Value("${costguard.clickhouse.timeout:PT30S}") Duration timeout) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.database = database;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean configured() {
        return !url.isBlank();
    }

    private String send(String sql, String what, String extraParams) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/?database="
                        + URLEncoder.encode(database, StandardCharsets.UTF_8) + extraParams))
                .timeout(timeout)
                .header("X-ClickHouse-User", user)
                .header("X-ClickHouse-Key", password)
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable("ClickHouse " + what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("ClickHouse " + what + ": interrupted");
        }
        if (response.statusCode() != 200) {
            throw new Unavailable("ClickHouse " + what + ": HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** A statement that changes data, waited for if it is a mutation ({@code mutations_sync=2}). */
    public void execute(String sql, String what) {
        send(sql, what, "&mutations_sync=2");
    }

    /** Rows of a query, as tab-separated text: the query must not end with a FORMAT clause. */
    public List<String[]> rows(String sql, String what) {
        String body = send(sql + " FORMAT TabSeparated", what, "");
        List<String[]> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isEmpty()) {
                out.add(line.split("\t", -1));
            }
        }
        return out;
    }
}
