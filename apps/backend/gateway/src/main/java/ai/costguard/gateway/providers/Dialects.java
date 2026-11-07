package ai.costguard.gateway.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/** OpenAI and GigaChat share the chat completions shape; Anthropic and YandexGPT have their own. */
final class Dialects {

    private Dialects() {
    }

    static Dialect of(String provider) {
        return switch (provider) {
            case "anthropic" -> ANTHROPIC;
            case "yandex" -> YANDEX;
            default -> OPENAI;
        };
    }

    private static Optional<Long> number(JsonNode node) {
        return node == null || !node.canConvertToLong() ? Optional.empty() : Optional.of(node.asLong());
    }

    static final Dialect OPENAI = new Dialect() {
        public String model(JsonNode r) {
            return r.path("model").asText(null);
        }

        public String prompt(JsonNode r) {
            return Dialect.text(r.get("messages"));
        }

        public Optional<Long> maxTokens(JsonNode r) {
            return number(r.has("max_completion_tokens") ? r.get("max_completion_tokens") : r.get("max_tokens"));
        }

        public void maxTokens(ObjectNode r, long tokens) {
            if (r.has("max_completion_tokens")) {
                r.put("max_completion_tokens", tokens);
            } else {
                r.put("max_tokens", tokens);
            }
        }

        public Optional<long[]> usage(JsonNode r) {
            JsonNode u = r.get("usage");
            if (u == null || u.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new long[] {u.path("prompt_tokens").asLong(), u.path("completion_tokens").asLong()});
        }
    };

    static final Dialect ANTHROPIC = new Dialect() {
        public String model(JsonNode r) {
            return r.path("model").asText(null);
        }

        public String prompt(JsonNode r) {
            return Dialect.text(r.get("system")) + Dialect.text(r.get("messages"));
        }

        public Optional<Long> maxTokens(JsonNode r) {
            return number(r.get("max_tokens"));
        }

        public void maxTokens(ObjectNode r, long tokens) {
            r.put("max_tokens", tokens);
        }

        /** A message has usage on top; a stream has it in message_start and message_delta. */
        public Optional<long[]> usage(JsonNode r) {
            JsonNode u = r.has("usage") ? r.get("usage") : r.path("message").get("usage");
            if (u == null || u.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new long[] {u.path("input_tokens").asLong(), u.path("output_tokens").asLong()});
        }
    };

    static final Dialect YANDEX = new Dialect() {
        public String model(JsonNode r) {
            String uri = r.path("modelUri").asText("");
            return uri.contains("/") ? uri.substring(uri.indexOf('/', 6) + 1) : uri;
        }

        public String prompt(JsonNode r) {
            return Dialect.text(r.get("messages"));
        }

        public Optional<Long> maxTokens(JsonNode r) {
            return number(r.path("completionOptions").get("maxTokens"));
        }

        public void maxTokens(ObjectNode r, long tokens) {
            JsonNode options = r.get("completionOptions");
            ObjectNode o = options instanceof ObjectNode existing ? existing : r.putObject("completionOptions");
            o.put("maxTokens", Long.toString(tokens));
        }

        public Optional<long[]> usage(JsonNode r) {
            JsonNode u = r.path("result").get("usage");
            if (u == null || u.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new long[] {u.path("inputTextTokens").asLong(), u.path("completionTokens").asLong()});
        }
    };
}
