package ai.costguard.core.shared.names;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every phrase the anomaly detector writes (apps/python/anomaly), as an English company reads it (ADR 0074). */
class DetectorTextTest {

    /** What the detector writes, exactly as its f-strings make it, and the English of each. */
    static final Map<String, String> PHRASES = new LinkedHashMap<>();

    static {
        // risk.py
        PHRASES.put("Траты за 5 минут (1250.00) в 24 раз выше обычных", "Spend in 5 minutes (1250.00) is 24 times the usual");
        PHRASES.put("Новый поставщик для агента: Acme Data", "A new vendor for the agent: Acme Data");
        PHRASES.put("Новый поставщик для агента: Acme Data, сумма 900.00 при обычных 40.00",
                "A new vendor for the agent: Acme Data, amount 900.00 against the usual 40.00");
        PHRASES.put("Дробление покупки: 4 похожих платежей Acme Data за 5 минут, всего 396.00",
                "A split purchase: 4 similar payments to Acme Data in 5 minutes, 396.00 in all");
        PHRASES.put("Операция ночью (3:00), агент обычно ночью не работает",
                "An operation at night (3:00); the agent does not usually work at night");
        PHRASES.put("7 из 12 операций за 5 минут отклонены", "7 of 12 operations in 5 minutes were refused");
        PHRASES.put("Петля в задаче demo-1: 12 одинаковых вызовов за 5 минут",
                "A loop in task demo-1: 12 identical calls in 5 minutes");
        PHRASES.put("Похоже на петлю в задаче demo-1: 6 одинаковых вызовов",
                "Looks like a loop in task demo-1: 6 identical calls");
        PHRASES.put("Задача demo-1: 55 шагов за 5 минут", "Task demo-1: 55 steps in 5 minutes");
        PHRASES.put("Задача demo-1: контекст вырос в 6 раз за 9 шагов", "Task demo-1: the context grew 6 times in 9 steps");
        // models.py and scorer.py
        PHRASES.put("Isolation Forest: операция необычна для агента (0.72)",
                "Isolation Forest: the operation is unusual for the agent (0.72)");
        PHRASES.put("Онлайн-модель river: поведение отличается от обычного (0.41)",
                "Online model (river): the behaviour differs from the usual (0.41)");
        PHRASES.put("Последние 5 минут без отклонений", "No deviations in the last 5 minutes");
        // detector.py: titles of findings
        PHRASES.put("Возможно двойное списание: Figma, 45.00 USD 07.11.2026, 2 раза",
                "Possible double charge: Figma, 45.00 USD on 2026-11-07, 2 times");
        PHRASES.put("Всплеск трат: agent 3f2a, 812.40 USD за 12.11.2026 при обычных 95.10",
                "Spend spike: agent 3f2a, 812.40 USD on 2026-11-12 against the usual 95.10");
    }

    @Test
    void everyPhraseOfTheDetectorReadsInEnglish() {
        PHRASES.forEach((ru, en) -> {
            String out = DetectorText.in(Language.EN, ru);
            assertThat(out).as(ru).isEqualTo(en);
            assertThat(out.codePoints().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC))
                    .as(out).isTrue();
        });
    }

    @Test
    void aRussianCompanyKeepsTheTextAsItCame() {
        PHRASES.keySet().forEach(ru -> assertThat(DetectorText.in(Language.RU, ru)).isEqualTo(ru));
    }

    @Test
    void anUnknownTextStaysAsItIsRatherThanWrong() {
        assertThat(DetectorText.in(Language.EN, "Новая фраза детектора")).isEqualTo("Новая фраза детектора");
        assertThat(DetectorText.in(Language.EN, null)).isNull();
        // a vendor whose name looks like a group reference is copied as it is
        assertThat(DetectorText.in(Language.EN, "Новый поставщик для агента: $1 \\ Ltd"))
                .isEqualTo("A new vendor for the agent: $1 \\ Ltd");
    }
}
