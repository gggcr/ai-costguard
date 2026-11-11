package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.LicensePool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which subscription of the company a purchase asks for (ADR 0062). First the names as they are, then what people
 * call programs: brands and their products (ChatGPT Plus is OpenAI, Teams is Microsoft 365, Photoshop is Adobe),
 * Russian spellings (Фигма), a typo or two. Then, when a model is configured, the model picks among the company's
 * subscriptions through the CostGuard gateway; it can only name one of them, never invent one.
 */
@Component
class ProgramMatcher {

    private static final Logger log = LoggerFactory.getLogger(ProgramMatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A word people use → the brand a subscription is usually named after. */
    private static final Map<String, String> BRANDS = Map.ofEntries(
            Map.entry("chatgpt", "openai"), Map.entry("gpt", "openai"), Map.entry("dall e", "openai"),
            Map.entry("claude", "anthropic"),
            Map.entry("copilot", "github copilot"),
            Map.entry("teams", "microsoft 365"), Map.entry("outlook", "microsoft 365"),
            Map.entry("office", "microsoft 365"), Map.entry("word", "microsoft 365"),
            Map.entry("excel", "microsoft 365"), Map.entry("powerpoint", "microsoft 365"),
            Map.entry("onedrive", "microsoft 365"), Map.entry("sharepoint", "microsoft 365"),
            Map.entry("photoshop", "adobe"), Map.entry("illustrator", "adobe"), Map.entry("acrobat", "adobe"),
            Map.entry("premiere", "adobe"), Map.entry("after effects", "adobe"), Map.entry("lightroom", "adobe"),
            Map.entry("indesign", "adobe"), Map.entry("creative cloud", "adobe"),
            Map.entry("intellij", "jetbrains"), Map.entry("pycharm", "jetbrains"), Map.entry("webstorm", "jetbrains"),
            Map.entry("rider", "jetbrains"), Map.entry("goland", "jetbrains"), Map.entry("datagrip", "jetbrains"),
            Map.entry("jira", "atlassian"), Map.entry("confluence", "atlassian"),
            Map.entry("gmail", "google workspace"), Map.entry("google docs", "google workspace"),
            Map.entry("фигма", "figma"), Map.entry("ноушн", "notion"), Map.entry("ноушен", "notion"),
            Map.entry("слак", "slack"), Map.entry("зум", "zoom"), Map.entry("миро", "miro"),
            Map.entry("джира", "jira"), Map.entry("канва", "canva"), Map.entry("фотошоп", "adobe"),
            Map.entry("чатгпт", "openai"), Map.entry("чат гпт", "openai"), Map.entry("гитхаб", "github"),
            Map.entry("тимс", "microsoft 365"), Map.entry("эксель", "microsoft 365"),
            Map.entry("ворд", "microsoft 365"), Map.entry("офис", "microsoft 365"));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String gateway;
    private final String apiKey;
    private final String provider;
    private final String model;

    ProgramMatcher(@Value("${costguard.purchases.llm.gateway-url:http://gateway:8081}") String gateway,
            @Value("${costguard.purchases.llm.api-key:}") String apiKey,
            @Value("${costguard.purchases.llm.provider:openai}") String provider,
            @Value("${costguard.purchases.llm.model:}") String model) {
        this.gateway = gateway.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.provider = provider;
        this.model = model;
    }

    record Match(LicensePool.Offer offer, String how) {
    }

    Optional<Match> match(LicensePool pool, int idleDays, String product, String vendor) {
        return match(pool, idleDays, product, vendor, true);
    }

    /** {@code model} false: names, brands and typos only (a sign-in log has thousands of names). */
    Optional<Match> match(LicensePool pool, int idleDays, String product, String vendor, boolean model) {
        Optional<LicensePool.Offer> exact = pool.match(product, idleDays)
                .or(() -> vendor == null ? Optional.empty() : pool.match(vendor, idleDays));
        if (exact.isPresent()) {
            return Optional.of(new Match(exact.get(), "name"));
        }
        List<LicensePool.Offer> offers = pool.offers(idleDays);
        if (offers.isEmpty()) {
            return Optional.empty();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String text : new String[] {product, vendor}) {
            if (text != null && !text.isBlank()) {
                String clean = Names.clean(text);
                wanted.add(clean);
                BRANDS.forEach((word, brand) -> {
                    if ((" " + clean + " ").contains(" " + word + " ")) {
                        wanted.add(brand);
                    }
                });
            }
        }
        List<LicensePool.Offer> found = new ArrayList<>();
        for (LicensePool.Offer o : offers) {
            String p = Names.clean(o.product());
            String v = Names.clean(o.vendor());
            for (String w : wanted) {
                if (same(w, p) || same(w, v) || (w.length() >= 4 && (p.contains(w) || v.contains(w)))) {
                    found.add(o);
                    break;
                }
            }
        }
        if (found.size() == 1) {
            return Optional.of(new Match(found.get(0), "alias"));
        }
        if (!model) {
            return Optional.empty();
        }
        return ask(found.size() > 1 ? found : offers, product, vendor).map(o -> new Match(o, "model"));
    }

    /** Equal, or a typo apart: one edit in five letters, two in longer names. */
    static boolean same(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int shorter = Math.min(a.length(), b.length());
        if (shorter < 5) {
            return false;
        }
        return distance(a, b) <= (shorter < 9 ? 1 : 2);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    boolean modelConfigured() {
        return !apiKey.isBlank() && !model.isBlank();
    }

    /** The model names the number of the subscription, or 0; anything else is no match. */
    private Optional<LicensePool.Offer> ask(List<LicensePool.Offer> offers, String product, String vendor) {
        if (!modelConfigured() || offers.size() > 200) {
            return Optional.empty();
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < offers.size(); i++) {
            list.append(i + 1).append(". ").append(offers.get(i).product()).append(" (")
                    .append(offers.get(i).vendor()).append(")\n");
        }
        String question = "Сотрудник хочет купить: " + product + (vendor == null ? "" : " (" + vendor + ")")
                + ".\nПодписки компании:\n" + list
                + "Это та же программа, что одна из подписок (тот же продукт, а не похожий)? "
                + "Ответь только номером подписки или 0, если такой нет.";
        ObjectNode body = JSON.createObjectNode().put("model", model).put("max_tokens", 5).put("temperature", 0);
        body.putArray("messages").addObject().put("role", "user").put("content", question);
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(gateway + "/v1/providers/"
                            + provider + "/v1/chat/completions")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-CostGuard-Task", "purchase-match")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Purchase match by model: HTTP {}", r.statusCode());
                return Optional.empty();
            }
            JsonNode answer = JSON.readTree(r.body()).path("choices").path(0).path("message").path("content");
            String digits = answer.asText().replaceAll("\\D", "");
            int n = digits.isEmpty() || digits.length() > 3 ? 0 : Integer.parseInt(digits);
            return n >= 1 && n <= offers.size() ? Optional.of(offers.get(n - 1)) : Optional.empty();
        } catch (IOException e) {
            log.warn("Purchase match by model failed: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
