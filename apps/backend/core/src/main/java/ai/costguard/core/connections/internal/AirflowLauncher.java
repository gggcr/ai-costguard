package ai.costguard.core.connections.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * «Синхронизировать сейчас» for an agent that runs in Airflow (ADR 0019, addendum): starts the DAG
 * {@code costguard_connector_sync} through the Airflow 2 REST API, so the pull happens now and not on the next hourly
 * round. Without {@code costguard.connections.airflow.url} it does nothing and the request waits for the round, as
 * before. The run id is the connection and the request time, so a repeated request does not start a second run.
 */
@Component
class AirflowLauncher {

    private static final Logger log = LoggerFactory.getLogger(AirflowLauncher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String url;
    private final String authorization;
    private final String dag;

    AirflowLauncher(@Value("${costguard.connections.airflow.url:}") String url,
            @Value("${costguard.connections.airflow.username:}") String username,
            @Value("${costguard.connections.airflow.password:}") String password,
            @Value("${costguard.connections.airflow.dag:costguard_connector_sync}") String dag) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.authorization = username.isBlank() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.dag = dag;
    }

    boolean configured() {
        return !url.isBlank();
    }

    /**
     * True when Airflow started the run (or already had it). A refusal is logged and returns false: the sync stays
     * requested in {@code connectors.config.v1}, and the agent pulls it on its next round anyway.
     */
    boolean launch(UUID tenantId, UUID connectionId, String type, Instant requestedAt) {
        if (!configured()) {
            return false;
        }
        Map<String, Object> body = Map.of("dag_run_id", "manual-" + connectionId + "-" + requestedAt.toEpochMilli(),
                "conf", Map.of("tenant_id", tenantId.toString(), "connection_id", connectionId.toString(),
                        "connector", type));
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/api/v1/dags/" + dag + "/dagRuns"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2 || status == 409) { // 409: this request already started a run
                return true;
            }
            log.warn("Airflow did not start {} for connection {}: {}", dag, connectionId, status);
            return false;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            log.warn("Airflow is not reachable at {}: {}", url, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
