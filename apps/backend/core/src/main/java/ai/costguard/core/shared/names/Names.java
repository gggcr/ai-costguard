package ai.costguard.core.shared.names;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How two sources agree on a vendor or a person. The same rules as costguard_common.normalized in Python, so a
 * vendor typed by hand, found in 1C and read from an invoice land on one record.
 */
public final class Names {

    private static final Pattern PUNCTUATION = Pattern.compile("[\"'«»“”„`.,()]");
    private static final Pattern LEGAL_FORMS = Pattern.compile(
            "(?U)\\b(ооо|оао|зао|пао|ао|ип|нко|ano|llc|ltd|inc|gmbh|corp|co|limited|"
                    + "общество с ограниченной ответственностью|акционерное общество|"
                    + "публичное акционерное общество|индивидуальный предприниматель)\\b");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private Names() {
    }

    /** 'ООО «Ромашка»' and 'Ромашка ООО' give 'ромашка'. */
    public static String clean(String name) {
        if (name == null) {
            return "";
        }
        String text = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        text = PUNCTUATION.matcher(text).replaceAll(" ");
        text = LEGAL_FORMS.matcher(text).replaceAll(" ");
        return String.join(" ", text.trim().split("\\s+")).trim();
    }

    /** Ten or twelve digits, or null. */
    public static String inn(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() == 10 || digits.length() == 12 ? digits : null;
    }

    public static String email(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(text).matches() ? text : null;
    }
}
