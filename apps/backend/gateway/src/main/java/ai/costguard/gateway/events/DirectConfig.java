package ai.costguard.gateway.events;

import ai.costguard.events.wire.SingleObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway without Kafka and the Schema Registry (ADR 0080): state comes from the core's feed, operations go to
 * the core's inbox, both under the shared INTERNAL_TOKEN. For small installs: PostgreSQL and Redis are all the
 * gateway and the core need.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "costguard.gateway.transport", havingValue = "direct")
class DirectConfig {

    static final int MIN_TOKEN = 32;

    private final URI core;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    DirectConfig(@Value("${costguard.gateway.core-url:http://localhost:8080}") URI core,
            @Value("${costguard.gateway.internal-token:}") String token) {
        if (token.length() < MIN_TOKEN) {
            throw new IllegalStateException("EVENTS_TRANSPORT=direct needs INTERNAL_TOKEN of at least " + MIN_TOKEN
                    + " characters, the same for the core and the gateway (for example: openssl rand -hex 32).");
        }
        this.core = core;
        this.token = token;
    }

    @Bean
    CoreFeed coreFeed(StateApplier state) {
        return new CoreFeed(http, core, token, state);
    }

    /** Not delivered unless the core answered 2xx; the disk buffer keeps the rest for the next replay. */
    @Bean
    OperationLog operationLog(@Value(EventsConfig.BUFFER) Path buffer) {
        URI inbox = core.resolve("/internal/v1/gateway/operations");
        return new OperationLog((key, op) -> http.sendAsync(HttpRequest.newBuilder(inbox)
                        .header("X-Internal-Token", token).header("Content-Type", "application/octet-stream")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(SingleObject.encode(op))).build(),
                HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("the core answered " + response.statusCode());
                    }
                    return response;
                }), buffer);
    }
}
