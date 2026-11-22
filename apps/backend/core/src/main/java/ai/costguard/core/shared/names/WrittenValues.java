package ai.costguard.core.shared.names;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How people of a country write amounts and dates in a sheet (ADR 0072): the rules of costguard_ingest.values in
 * Python, for the sheets the core reads itself (a pay sheet, an employee's purchases). 1,500 is fifteen hundred in the
 * United States and one and a half in Russia; 03/04/2026 is March 4 there and 3 April in Britain. The other values of
 * the same column decide first, the company's country next. A value neither decides is refused, not guessed: the
 * sheet has no review step, and the row comes back with its reason.
 */
public final class WrittenValues {

    /**
     * The separators of 1,234.56 ({@code decimal} '.', {@code group} ','), and whether 03/04/2026 is March 4
     * ({@code monthFirst} true) or 3 April (false); null where not known. {@code settled}: the column showed its
     * separators itself. {@code sheet}: an .xlsx, where a plain number with a dot is a number cell, exact.
     */
    public record Style(Character decimal, Character group, Boolean monthFirst, boolean settled, boolean sheet) {

        public static final Style UNKNOWN = new Style(null, null, null, false, false);

        public Style inSheet() {
            return new Style(decimal, group, monthFirst, settled, true);
        }
    }

    private static final Set<String> DOT_COMMA = Set.of(("US GB IE AU NZ CA IN PK BD LK NP SG MY PH TH HK MO CN TW JP "
            + "KR IL MX GT HN SV NI DO PR PA NG GH KE UG TZ MT AE SA QA KW BH OM JO").split(" "));
    private static final Set<String> COMMA_DOT = Set.of(("DE AT NL BE LU IT ES DK IS GR TR BR AR CL CO UY PY VE EC BO "
            + "ID VN RS HR SI BA ME MK RO").split(" "));
    private static final Set<String> COMMA_SPACE = Set.of(("RU BY KZ UA UZ KG TJ TM AM AZ GE MD FR PL CZ SK HU SE NO "
            + "FI EE LV LT BG PT").split(" "));
    private static final Set<String> DOT_APOSTROPHE = Set.of("CH", "LI");
    private static final Set<String> MONTH_FIRST = Set.of("US PR GU VI AS MP UM FM MH PW BZ PH".split(" "));
    /** Day first, and dates with slashes are the country's own; elsewhere they come from a foreign program. */
    private static final Set<String> DAY_FIRST = Set.of(("GB IE AU NZ IN PK BD LK SG MY HK MO MT IL FR BE LU ES IT PT "
            + "GR TR BR AR CL CO UY PY VE EC BO PE MX GT HN SV NI DO PA ID VN NG GH KE UG TZ AE SA QA KW BH OM JO EG")
            .split(" "));

    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0\\u202f']");
    private static final List<String> CURRENCY_SIGNS = List.of("₽", "руб", "р.", "rub", "rur", "$", "usd", "€", "eur",
            "¥", "cny", "юань");
    private static final Pattern NUMBER = Pattern.compile("[+-]?[\\d.,]*\\d[\\d.,]*");
    private static final Pattern PLAIN = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ].*)?");
    private static final Pattern YEAR_FIRST = Pattern.compile("(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4}|\\d{2})");
    private static final List<DateTimeFormatter> WORDS = List.of(words("MMM d, uuuu"), words("MMMM d, uuuu"),
            words("MMM d uuuu"), words("MMMM d uuuu"), words("d MMM uuuu"), words("d MMMM uuuu"),
            words("d-MMM-uuuu"));

    private WrittenValues() {
    }

    private static DateTimeFormatter words(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** The style of a country, ISO 3166-1 alpha-2; {@link Style#UNKNOWN} for a country not listed or none. */
    public static Style of(String country) {
        String code = country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
        Character decimal = null;
        Character group = null;
        if (DOT_COMMA.contains(code)) {
            decimal = '.';
            group = ',';
        } else if (COMMA_DOT.contains(code)) {
            decimal = ',';
            group = '.';
        } else if (COMMA_SPACE.contains(code)) {
            decimal = ',';
            group = ' ';
        } else if (DOT_APOSTROPHE.contains(code)) {
            decimal = '.';
            group = '\'';
        }
        Boolean monthFirst = MONTH_FIRST.contains(code) ? Boolean.TRUE
                : DAY_FIRST.contains(code) ? Boolean.FALSE : null;
        return new Style(decimal, group, monthFirst, false, false);
    }

    private static String clean(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        for (String sign : CURRENCY_SIGNS) {
            t = t.replace(sign, "");
        }
        return SPACES.matcher(t).replaceAll("");
    }

    private static boolean groupingLike(String head, String tail) {
        String digits = head.replaceFirst("^[+-]", "");
        return tail.length() == 3 && tail.chars().allMatch(Character::isDigit) && !digits.isEmpty()
                && digits.length() <= 3 && digits.chars().allMatch(Character::isDigit) && digits.charAt(0) != '0';
    }

    private static boolean grouped(String[] parts) {
        String digits = parts[0].replaceFirst("^[+-]", "");
        if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() != 3 || !parts[i].chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    /** The decimal separator of a value by itself, when only one fits. */
    private static Character decimalOf(String cleaned) {
        int dots = count(cleaned, '.');
        int commas = count(cleaned, ',');
        if (dots > 0 && commas > 0) {
            return cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.') ? ',' : '.';
        }
        if (dots > 1 || commas > 1) {
            return dots > 1 ? ',' : '.';
        }
        if (dots + commas == 1) {
            char sep = dots == 1 ? '.' : ',';
            int at = cleaned.indexOf(sep);
            if (!groupingLike(cleaned.substring(0, at), cleaned.substring(at + 1))) {
                return sep;
            }
        }
        return null;
    }

    /** The separators the amounts of one column show by themselves, when they agree; else the company's. */
    public static Style amounts(List<String> cells, Style company) {
        Set<Character> decimals = new HashSet<>();
        for (String cell : cells) {
            if (cell == null || cell.isBlank() || company.sheet() && PLAIN.matcher(cell.strip()).matches()) {
                continue;
            }
            String cleaned = clean(cell);
            if (NUMBER.matcher(cleaned).matches()) {
                Character decimal = decimalOf(cleaned);
                if (decimal != null) {
                    decimals.add(decimal);
                }
            }
        }
        if (decimals.size() != 1) {
            return company;
        }
        char decimal = decimals.iterator().next();
        return new Style(decimal, decimal == '.' ? ',' : '.', company.monthFirst(), true, company.sheet());
    }

    /** An amount; empty when it is no number, or when only the country could say what it is and none does. */
    public static Optional<BigDecimal> amount(String text, Style style) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (style.sheet() && PLAIN.matcher(text.strip()).matches()) {
            return Optional.of(new BigDecimal(text.strip())); // a number cell of the workbook
        }
        String cleaned = clean(text);
        boolean negative = cleaned.startsWith("(") && cleaned.endsWith(")");
        cleaned = negative ? cleaned.substring(1, cleaned.length() - 1) : cleaned;
        if (!NUMBER.matcher(cleaned).matches()) {
            return Optional.empty();
        }
        String plain = plain(cleaned, style);
        if (plain == null) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(plain);
            return Optional.of(negative ? value.negate() : value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String plain(String cleaned, Style style) {
        int dots = count(cleaned, '.');
        int commas = count(cleaned, ',');
        if (dots > 0 && commas > 0) {
            char decimal = cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.') ? ',' : '.';
            char group = decimal == ',' ? '.' : ',';
            return count(cleaned, decimal) == 1 ? cleaned.replace(String.valueOf(group), "").replace(decimal, '.')
                    : null;
        }
        if (dots == 0 && commas == 0) {
            return cleaned;
        }
        char sep = dots > 0 ? '.' : ',';
        String[] parts = cleaned.split(Pattern.quote(String.valueOf(sep)), -1);
        if (parts.length > 2) {
            return grouped(parts) && Character.valueOf(sep).equals(style.group()) ? String.join("", parts) : null;
        }
        String head = parts[0];
        String tail = parts[1];
        if (!groupingLike(head, tail)) {
            return head + "." + tail;
        }
        if (Character.valueOf(sep).equals(style.group())) {
            return head + tail;
        }
        return Character.valueOf(sep).equals(style.decimal()) && style.settled() ? head + "." + tail : null;
    }

    private static int count(String text, char c) {
        return (int) text.chars().filter(x -> x == c).count();
    }

    /** The order the dates of one column show by themselves (25/10/2026, 10/25/2026); else the company's. */
    public static Style dates(List<String> cells, Style company) {
        Set<Boolean> votes = new HashSet<>();
        for (String cell : cells) {
            if (cell == null) {
                continue;
            }
            Matcher m = NUMERIC.matcher(cell.strip().split(" ")[0]);
            if (!m.matches() || m.group(2).equals(".")) {
                continue;
            }
            int first = Integer.parseInt(m.group(1));
            int second = Integer.parseInt(m.group(3));
            if (first > 12 && second <= 12) {
                votes.add(Boolean.FALSE);
            } else if (second > 12 && first <= 12) {
                votes.add(Boolean.TRUE);
            }
        }
        if (votes.size() != 1) {
            return company;
        }
        return new Style(company.decimal(), company.group(), votes.iterator().next(), company.settled(),
                company.sheet());
    }

    /**
     * A day: 2026-10-15, 15.10.2026, 10/15/2026 or 15/10/2026 by the style, Oct 15, 2026; empty when it is no date,
     * or when only the country could tell the day from the month and none does.
     */
    public static Optional<LocalDate> date(String text, Style style) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        Optional<LocalDate> found = day(t, style);
        return found.isEmpty() && t.contains(" ") ? day(t.split(" ")[0], style) : found;
    }

    private static Optional<LocalDate> day(String t, Style style) {
        try {
            Matcher m = ISO.matcher(t);
            if (m.matches()) {
                return Optional.of(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)));
            }
            m = YEAR_FIRST.matcher(t);
            if (m.matches()) {
                return Optional.of(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)));
            }
            m = NUMERIC.matcher(t);
            if (m.matches()) {
                return numeric(m, style);
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        for (DateTimeFormatter f : WORDS) {
            try {
                return Optional.of(LocalDate.parse(t, f));
            } catch (DateTimeParseException e) {
                // the next way of writing it
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> numeric(Matcher m, Style style) {
        int first = num(m, 1);
        int second = num(m, 3);
        String yearText = m.group(4);
        int year = Integer.parseInt(yearText);
        if (yearText.length() == 2) {
            year += year >= 69 ? 1900 : 2000;
        }
        boolean monthFirst;
        if (m.group(2).equals(".")) {
            monthFirst = false;
        } else if (first > 12 && second <= 12) {
            monthFirst = false;
        } else if (second > 12 && first <= 12) {
            monthFirst = true;
        } else if (first <= 12 && style.monthFirst() != null) {
            monthFirst = style.monthFirst();
        } else {
            return Optional.empty();
        }
        return Optional.of(monthFirst ? LocalDate.of(year, first, second) : LocalDate.of(year, second, first));
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }
}
