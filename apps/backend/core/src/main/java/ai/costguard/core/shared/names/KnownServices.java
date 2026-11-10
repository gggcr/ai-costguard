package ai.costguard.core.shared.names;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Services a payment can be recognised by. The SaaS names follow costguard_common.payments.SERVICES in Python, so a
 * card payment the connectors attributed to "Notion" is software here too. AI providers are named by one key each,
 * so "OpenAI", "OpenAI, Inc" in a mandate and "CHATGPT SUBSCRIPTION" on a card agree.
 */
public final class KnownServices {

    /** Cleaned names (see {@link Names#clean}) of software sold as a service. */
    private static final Set<String> SAAS = Set.of(
            "notion", "slack", "zoom", "figma", "miro", "github", "gitlab", "atlassian", "jira", "confluence",
            "trello", "adobe", "canva", "dropbox", "asana", "monday com", "hubspot", "salesforce", "mailchimp",
            "twilio", "jetbrains", "docker", "vercel", "heroku", "digitalocean", "hetzner", "cloudflare",
            "amazon web services", "aws", "google workspace", "google cloud", "microsoft 365", "office 365",
            "azure", "grammarly", "deepl", "loom", "calendly", "1password", "linear", "intercom", "zendesk",
            "битрикс24", "bitrix24", "amocrm", "яндекс 360", "yandex 360", "kaiten", "пачка", "pachca", "weeek",
            "контур толк", "trueconf", "мойсклад", "retailcrm", "yonote", "pixso", "selectel", "timeweb");

    /** Words of a name or purpose -> the AI provider. */
    private static final Map<String, String> AI = Map.ofEntries(
            Map.entry("openai", "openai"), Map.entry("chatgpt", "openai"),
            Map.entry("anthropic", "anthropic"), Map.entry("claude", "anthropic"),
            Map.entry("midjourney", "midjourney"),
            Map.entry("perplexity", "perplexity"),
            Map.entry("mistral", "mistral"),
            Map.entry("deepseek", "deepseek"),
            Map.entry("gemini", "google"),
            Map.entry("gigachat", "sber"), Map.entry("гигачат", "sber"),
            Map.entry("yandexgpt", "yandex"), Map.entry("yandex gpt", "yandex"), Map.entry("яндексgpt", "yandex"),
            Map.entry("алиса про", "yandex"),
            Map.entry("cursor", "cursor"),
            Map.entry("github copilot", "github-copilot"),
            Map.entry("elevenlabs", "elevenlabs"),
            Map.entry("runway", "runway"));

    private KnownServices() {
    }

    /** A known SaaS by its name or by a word of it ("Notion Labs" is Notion). */
    public static boolean saas(String name) {
        String cleaned = Names.clean(name);
        return SAAS.contains(cleaned) || SAAS.stream().anyMatch(s -> word(cleaned, s));
    }

    /** The AI provider the text names, if any: a vendor name, a mandate's vendor or a payment purpose. */
    public static Optional<String> aiProvider(String text) {
        String cleaned = Names.clean(text);
        return AI.entrySet().stream().filter(e -> word(cleaned, e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue);
    }

    private static boolean word(String text, String name) {
        return !text.isEmpty() && Pattern.compile("(?U)(?<![\\w])" + Pattern.quote(name) + "(?![\\w])")
                .matcher(text).find();
    }
}
