package ai.costguard.core.shared.names;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The language the core writes a company's texts in (ADR 0074): notifications, e-mail, Telegram, findings, notices of
 * the cabinet, stamps on documents. A company names its own; one that did not gets Russian where its country speaks
 * it and English elsewhere, and a company from before registration asked for a country stays Russian.
 */
public enum Language {
    RU, EN;

    /** Countries where the company's people read Russian at work. */
    private static final Set<String> RUSSIAN = Set.of("RU", "BY", "KZ", "KG", "TJ", "UZ", "AM", "AZ", "MD");

    /** {@code ru} or {@code en}, as the API and the database write it. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The text in this language. */
    public String pick(String ru, String en) {
        return this == RU ? ru : en;
    }

    public static Optional<Language> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code.strip().toLowerCase(Locale.ROOT)) {
            case "ru" -> Optional.of(RU);
            case "en" -> Optional.of(EN);
            default -> Optional.empty();
        };
    }

    /** The language of a country; Russian for a company without one. */
    public static Language defaultFor(String country) {
        if (country == null || country.isBlank()) {
            return RU;
        }
        return RUSSIAN.contains(country.strip().toUpperCase(Locale.ROOT)) ? RU : EN;
    }

    /** The company's language: the one it named, else its country's. */
    public static Language of(String code, String country) {
        return parse(code).orElseGet(() -> defaultFor(country));
    }
}
