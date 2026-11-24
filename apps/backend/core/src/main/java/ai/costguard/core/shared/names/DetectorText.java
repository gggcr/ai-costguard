package ai.costguard.core.shared.names;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts of the anomaly detector (apps/python/anomaly) in the company's language (ADR 0074). The detector does not
 * know the company and writes Russian; the core, which does, puts each of its fixed phrases into English for an
 * English company as the signal or the finding arrives. A text that matches none of them stays as it came: a phrase
 * added to the detector shows in Russian until it is added here, never wrong. The test lists every phrase the
 * detector writes.
 */
public final class DetectorText {

    private record Phrase(Pattern ru, String en) {
    }

    /** {@code dd.mm.yyyy} of the detector's findings, as ISO in English. */
    private static final Pattern DAY = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");

    // risk.py, models.py and scorer.py: reasons of agent.risk-signals.v1; detector.py: titles of findings.v1
    private static final List<Phrase> PHRASES = List.of(
            phrase("Траты за 5 минут \\((.+)\\) в (\\d+) раз выше обычных",
                    "Spend in 5 minutes ($1) is $2 times the usual"),
            phrase("Новый поставщик для агента: (.+), сумма (\\S+) при обычных (\\S+)",
                    "A new vendor for the agent: $1, amount $2 against the usual $3"),
            phrase("Новый поставщик для агента: (.+)", "A new vendor for the agent: $1"),
            phrase("Дробление покупки: (\\d+) похожих платежей (.+) за 5 минут, всего (\\S+)",
                    "A split purchase: $1 similar payments to $2 in 5 minutes, $3 in all"),
            phrase("Операция ночью \\((\\d+):00\\), агент обычно ночью не работает",
                    "An operation at night ($1:00); the agent does not usually work at night"),
            phrase("(\\d+) из (\\d+) операций за 5 минут отклонены", "$1 of $2 operations in 5 minutes were refused"),
            phrase("Петля в задаче (.+): (\\d+) одинаковых вызовов за 5 минут",
                    "A loop in task $1: $2 identical calls in 5 minutes"),
            phrase("Похоже на петлю в задаче (.+): (\\d+) одинаковых вызовов",
                    "Looks like a loop in task $1: $2 identical calls"),
            phrase("Задача (.+): (\\d+) шагов за 5 минут", "Task $1: $2 steps in 5 minutes"),
            phrase("Задача (.+): контекст вырос в (\\d+) раз за (\\d+) шагов",
                    "Task $1: the context grew $2 times in $3 steps"),
            phrase("Isolation Forest: операция необычна для агента \\((.+)\\)",
                    "Isolation Forest: the operation is unusual for the agent ($1)"),
            phrase("Онлайн-модель river: поведение отличается от обычного \\((.+)\\)",
                    "Online model (river): the behaviour differs from the usual ($1)"),
            phrase("Последние 5 минут без отклонений", "No deviations in the last 5 minutes"),
            phrase("Возможно двойное списание: (.+), (\\S+) (\\S+) (\\d{2}\\.\\d{2}\\.\\d{4}), (\\d+) раза",
                    "Possible double charge: $1, $2 $3 on $4, $5 times"),
            phrase("Всплеск трат: (.+), (\\S+) (\\S+) за (\\d{2}\\.\\d{2}\\.\\d{4}) при обычных (\\S+)",
                    "Spend spike: $1, $2 $3 on $4 against the usual $5"));

    private DetectorText() {
    }

    private static Phrase phrase(String ru, String en) {
        return new Phrase(Pattern.compile(ru), en);
    }

    /** The text in the company's language: as it came for Russian, in English when one of the phrases matches. */
    public static String in(Language language, String text) {
        if (language != Language.EN || text == null) {
            return text;
        }
        for (Phrase p : PHRASES) {
            Matcher m = p.ru().matcher(text);
            if (m.matches()) {
                return iso(m.replaceAll(p.en()));
            }
        }
        return text;
    }

    private static String iso(String text) {
        return DAY.matcher(text).replaceAll("$3-$2-$1");
    }
}
