package ai.costguard.gateway.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Puts the provider's own credentials on a request. GigaChat trades its authorization key for a 30-minute token. */
@Component
class ProviderCredentials {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Token(String value, Instant expires) {
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final WebClient http = WebClient.create();
    private final Clock clock;

    ProviderCredentials(Clock clock) {
        this.clock = clock;
    }

    Mono<Void> apply(String provider, ProviderProperties.Provider p, HttpHeaders headers) {
        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove("x-api-key");
        return switch (provider) {
            case "anthropic" -> {
                headers.set("x-api-key", p.apiKey());
                if (!headers.containsHeader("anthropic-version")) {
                    headers.set("anthropic-version", "2023-06-01");
                }
                yield Mono.empty();
            }
            case "yandex" -> {
                headers.set(HttpHeaders.AUTHORIZATION, "Api-Key " + p.apiKey());
                if (p.folderId() != null) {
                    headers.set("x-folder-id", p.folderId());
                }
                yield Mono.empty();
            }
            case "gigachat" -> gigachat(p).doOnNext(t -> headers.setBearerAuth(t)).then();
            default -> {
                headers.setBearerAuth(p.apiKey());
                yield Mono.empty();
            }
        };
    }

    private Mono<String> gigachat(ProviderProperties.Provider p) {
        Token cached = tokens.get("gigachat");
        if (cached != null && cached.expires().isAfter(clock.instant().plusSeconds(60))) {
            return Mono.just(cached.value());
        }
        return http.post().uri(p.oauthUrl())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + p.apiKey())
                .header("RqUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("scope", p.scope() == null ? "GIGACHAT_API_CORP" : p.scope()))
                .retrieve().bodyToMono(String.class)
                .map(body -> {
                    try {
                        JsonNode json = JSON.readTree(body);
                        Token token = new Token(json.path("access_token").asText(),
                                Instant.ofEpochMilli(json.path("expires_at").asLong()));
                        tokens.put("gigachat", token);
                        return token.value();
                    } catch (Exception e) {
                        throw new IllegalStateException("GigaChat returned no token", e);
                    }
                });
    }
}
