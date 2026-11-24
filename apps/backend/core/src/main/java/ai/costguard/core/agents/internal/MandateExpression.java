package ai.costguard.core.agents.internal;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles a mandate to the CEL expression the gateway evaluates. Variables: {@code model}, {@code vendor},
 * {@code cost} (double), {@code time} ("HH:mm") and {@code weekday}; see
 * {@link ai.costguard.platform.rules.OperationRules}.
 */
final class MandateExpression {

    static final String ALL_DAY = "круглосуточно";
    static final String WORKDAYS = "только рабочие дни";
    /** English names an API client may send instead; stored as the Russian ones, which the gateway reads. */
    static final String ALL_DAY_EN = "24/7";
    static final String WORKDAYS_EN = "workdays";
    static final String HOURS = ALL_DAY + "|" + WORKDAYS + "|" + ALL_DAY_EN + "|(?i:" + WORKDAYS_EN + ")"
            + "|([01]\\d|2[0-3]):[0-5]\\d[–-]([01]\\d|2[0-3]):[0-5]\\d";

    private MandateExpression() {
    }

    /**
     * What the API answers: "24/7" and "workdays" for the stored Russian names, a window as it is. Storage keeps the
     * Russian names, which published mandate versions were hashed with (ADR 0009).
     */
    static String code(String hours) {
        if (hours == null) {
            return null;
        }
        return switch (hours) {
            case ALL_DAY -> ALL_DAY_EN;
            case WORKDAYS -> WORKDAYS_EN;
            default -> hours;
        };
    }

    /** "24/7" and "workdays" as the stored names; anything else as it is. */
    static String canonical(String hours) {
        if (hours == null) {
            return null;
        }
        return switch (hours.strip().toLowerCase(java.util.Locale.ROOT)) {
            case ALL_DAY_EN -> ALL_DAY;
            case WORKDAYS_EN -> WORKDAYS;
            default -> hours;
        };
    }

    static String of(Mandate mandate) {
        // Models bind LLM calls only: a purchase through /v1/spend has no model, and requiring one denied them all.
        String expression = "(kind != " + quoted("LLM_CALL") + " || model in " + list(mandate.models()) + ")"
                + " && vendor in " + list(mandate.vendors())
                + " && cost <= " + decimal(mandate.maxPerOperation());
        return switch (mandate.hours()) {
            case ALL_DAY -> expression;
            case WORKDAYS -> expression + " && weekday <= 5";
            default -> expression + " && " + hours(mandate.hours());
        };
    }

    /** {@code cost} is a double, and CEL does not compare a double with an int literal such as 500. */
    private static String decimal(java.math.BigDecimal amount) {
        String plain = amount.stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /** A window such as 22:00–06:00 crosses midnight, so its two ends are alternatives. */
    private static String hours(String hours) {
        String from = hours.substring(0, 5);
        String to = hours.substring(6);
        String joint = from.compareTo(to) <= 0 ? " && " : " || ";
        return "(time >= " + quoted(from) + joint + "time < " + quoted(to) + ")";
    }

    private static String list(List<String> values) {
        return values.stream().map(MandateExpression::quoted).collect(Collectors.joining(", ", "[", "]"));
    }

    /** Names come from people, so they must not be able to break out of the string. */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
