package ai.costguard.gateway.metering;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * Counts input tokens before the call. OpenAI models use their own encoding. Other providers publish no tokenizer
 * for Java, so their count is cl100k plus a margin: an estimate that errs towards reserving more.
 */
@Component
public class TokenCounter {

    private static final double FOREIGN_MARGIN = 1.25;

    private final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

    public long count(String vendor, String model, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (isOpenAi(vendor)) {
            Encoding encoding = registry.getEncodingForModel(model == null ? "" : model)
                    .orElseGet(() -> registry.getEncoding(EncodingType.O200K_BASE));
            return encoding.countTokensOrdinary(text);
        }
        return (long) Math.ceil(registry.getEncoding(EncodingType.CL100K_BASE).countTokensOrdinary(text) * FOREIGN_MARGIN);
    }

    private static boolean isOpenAi(String vendor) {
        return vendor != null && vendor.toLowerCase().contains("openai");
    }
}
