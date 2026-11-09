package ai.costguard.platform.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Takes personal data and secrets out of a log line (ADR 0023). Services log ids, counts and codes, never a name, an
 * e-mail or a token; this is the safety net for what slips in anyway: an e-mail inside a database error, a token in
 * an exception of an HTTP client, a full name in a message from a connected system.
 *
 * <p>The rules match {@code costguard_common.logs} of the Python services; both run the cases of
 * {@code schemas/logging/masking-cases.json}. A name without a patronymic or initials cannot be told from ordinary
 * words, so the rule that matters most stays the first one: do not log people.
 */
public final class LogMasking {

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final String WORD = "[А-ЯЁ][а-яё]+";
    private static final String SURNAME = WORD + "(?:-" + WORD + ")?";
    private static final String PATRONYMIC = "[А-ЯЁ][а-яё]+(?:ич|вна|чна)";
    private static final String END = "(?![а-яёА-ЯЁ])";

    /**
     * Applied in order: credentials in URLs before e-mails, a name with the patronymic second before one with it
     * third, so "Согласовала Анна Сергеевна Кузнецова" loses the whole name and keeps the verb.
     */
    private static final List<Rule> RULES = List.of(
            rule("://[^/\\s:@]+:[^/\\s@]+@", "://***:***@"),
            rule("(?i)\\b(bearer|basic)\\s+(?=[A-Za-z0-9._~+/=-]*[0-9=_.-])[A-Za-z0-9._~+/=-]{8,}", "$1 ***"),
            rule("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+", "***"),
            rule("cg_(?:live|test)_[A-Za-z0-9_-]+", "cg_***"),
            rule("sk-[A-Za-z0-9_-]{12,}", "sk-***"),
            rule("(?i)\\b(password|passwd|pwd|secret|client_secret|token|access_token|refresh_token|api[_-]?key"
                    + "|access[_-]?key|secret[_-]?key)(\\s*[=:]\\s*)(\"?)[^\\s\",;&]+", "$1$2$3***"),
            rule("[A-Za-z0-9._%+-]+@((?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,})", "***@$1"),
            rule("(?<!\\d)\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{4}(?!\\d)", "***"),
            rule("(?<!\\d)\\d{3}-\\d{3}-\\d{3}[ -]\\d{2}(?!\\d)", "***"),
            rule("(?<![\\d+])(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}(?!\\d)", "***"),
            rule(WORD + "\\s+" + PATRONYMIC + "\\s+" + SURNAME + END, "***"),
            rule(SURNAME + "\\s+" + WORD + "\\s+" + PATRONYMIC + END, "***"),
            rule(SURNAME + "\\s+[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.", "***"),
            rule("[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.\\s?" + SURNAME + END, "***"));

    private LogMasking() {
    }

    private static Rule rule(String pattern, String replacement) {
        return new Rule(Pattern.compile(pattern), replacement);
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule r : RULES) {
            out = r.pattern().matcher(out).replaceAll(r.replacement());
        }
        return out;
    }
}
