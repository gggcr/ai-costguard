package ai.costguard.gateway.providers;

import ai.costguard.gateway.approvals.TaskFitModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Asks one of the gateway's providers on behalf of the AI assessor, with the gateway's own key (ADR 0011). The call
 * is a cost of CostGuard: it is not reserved in any budget of the company and not recorded in agent.operations.v1.
 * The answer is limited to a few tokens.
 */
public class ProviderTaskFitModel implements TaskFitModel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 8;

    private final String provider;
    private final ProviderProperties.Provider settings;
    private final String model;
    private final ProviderCredentials credentials;
    private final WebClient http;

    ProviderTaskFitModel(String provider, ProviderProperties.Provider settings, String model,
            ProviderCredentials credentials, WebClient http) {
        this.provider = provider;
        this.settings = settings;
        this.model = model;
        this.credentials = credentials;
        this.http = http;
    }

    @Override
    public Mono<String> ask(String system, String user) {
        HttpHeaders headers = new HttpHeaders();
        return credentials.apply(provider, settings, headers).then(Mono.defer(() -> http.post()
                .uri(URI.create(settings.url() + path()))
                .headers(h -> h.addAll(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(system, user).toString())
                .retrieve()
                .bodyToMono(String.class)
                .map(this::answer)));
    }

    private String path() {
        return switch (provider) {
            case "anthropic" -> "/v1/messages";
            case "yandex" -> "/foundationModels/v1/completion";
            default -> "/v1/chat/completions";
        };
    }

    ObjectNode body(String system, String user) {
        ObjectNode body = JSON.createObjectNode();
        switch (provider) {
            case "anthropic" -> {
                body.put("model", model).put("max_tokens", MAX_TOKENS).put("temperature", 0).put("system", system);
                body.putArray("messages").addObject().put("role", "user").put("content", user);
            }
            case "yandex" -> {
                body.put("modelUri", "gpt://" + settings.folderId() + "/" + model);
                body.putObject("completionOptions").put("temperature", 0).put("maxTokens", Integer.toString(MAX_TOKENS));
                var messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("text", system);
                messages.addObject().put("role", "user").put("text", user);
            }
            default -> {
                body.put("model", model).put("max_tokens", MAX_TOKENS).put("temperature", 0);
                var messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("content", system);
                messages.addObject().put("role", "user").put("content", user);
            }
        }
        return body;
    }

    String answer(String response) {
        try {
            JsonNode json = JSON.readTree(response);
            JsonNode text = switch (provider) {
                case "anthropic" -> json.path("content").path(0).path("text");
                case "yandex" -> json.path("result").path("alternatives").path(0).path("message").path("text");
                default -> json.path("choices").path(0).path("message").path("content");
            };
            if (!text.isTextual()) {
                throw new IllegalStateException("The assessor model gave no text");
            }
            return text.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The assessor model gave no JSON", e);
        }
    }
}
