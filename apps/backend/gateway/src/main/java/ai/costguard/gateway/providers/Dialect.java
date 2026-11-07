package ai.costguard.gateway.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/** How one provider's API names the model, the prompt, the output limit and the usage. */
interface Dialect {

    String model(JsonNode request);

    /** All text the model reads: it is what input tokens are counted from. */
    String prompt(JsonNode request);

    Optional<Long> maxTokens(JsonNode request);

    void maxTokens(ObjectNode request, long tokens);

    /** Input and output tokens from a response; for a stream, from the last chunk that carries usage. */
    Optional<long[]> usage(JsonNode response);

    static String text(JsonNode node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString();
    }

    private static void collect(JsonNode node, StringBuilder out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            out.append(node.asText()).append('\n');
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, out));
        }
    }
}
