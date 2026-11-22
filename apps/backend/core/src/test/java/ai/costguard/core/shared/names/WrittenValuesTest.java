package ai.costguard.core.shared.names;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.shared.names.WrittenValues.Style;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR 0072: the rules of costguard_ingest.values, for the sheets the core reads itself. */
class WrittenValuesTest {

    private static final Style US = WrittenValues.of("US");
    private static final Style RU = WrittenValues.of("RU");
    private static final Style DE = WrittenValues.of("DE");
    private static final Style GB = WrittenValues.of("GB");

    private static Optional<BigDecimal> amount(String text, Style style) {
        return WrittenValues.amount(text, style);
    }

    @Test
    void americanAmountsInAnAmericanCompany() {
        // before ADR 0072 the pay sheet read "1,500" as 1.5
        assertThat(amount("$1,500", US)).contains(new BigDecimal("1500"));
        assertThat(amount("1,234,567.89", US)).contains(new BigDecimal("1234567.89"));
        assertThat(amount("($1,200)", US)).contains(new BigDecimal("-1200"));
        assertThat(amount("1.5", US)).contains(new BigDecimal("1.5"));
        assertThat(amount("1.500", US)).isEmpty(); // a German 1500, or 1.5 with three decimals: refused
    }

    @Test
    void russianAndGermanAmounts() {
        assertThat(amount("40 000,00 ₽", RU)).contains(new BigDecimal("40000.00"));
        assertThat(amount("12,5", RU)).contains(new BigDecimal("12.5"));
        assertThat(amount("0,500", RU)).contains(new BigDecimal("0.500"));
        assertThat(amount("1,500", RU)).isEmpty(); // a Russian 1.5 or an American 1500
        assertThat(amount("1.500", DE)).contains(new BigDecimal("1500"));
        assertThat(amount("1.234,56", US)).contains(new BigDecimal("1234.56")); // two separators decide alone
        assertThat(amount("abc", RU)).isEmpty();
        assertThat(amount("", RU)).isEmpty();
        assertThat(amount("1,500", Style.UNKNOWN)).isEmpty();
    }

    @Test
    void theColumnDecidesBeforeTheCountry() {
        Style american = WrittenValues.amounts(List.of("1,234.56", "$1,500", "99.90"), RU);
        assertThat(amount("1,500", american)).contains(new BigDecimal("1500"));
        Style russian = WrittenValues.amounts(List.of("1 500,50", "12,5", "1.500"), US);
        assertThat(amount("1.500", russian)).contains(new BigDecimal("1500"));
        assertThat(amount("1,500", russian)).contains(new BigDecimal("1.500"));
        assertThat(WrittenValues.amounts(List.of("1,234.56", "1.234,56"), US)).isEqualTo(US);
        assertThat(WrittenValues.amounts(Arrays.asList(null, "", "abc"), RU)).isEqualTo(RU);
    }

    @Test
    void aWorkbookNumberCellIsExact() {
        Style sheet = RU.inSheet();
        assertThat(amount("150.125", sheet)).contains(new BigDecimal("150.125"));
        assertThat(amount("1 500,50", sheet)).contains(new BigDecimal("1500.50")); // a text cell, as people write
    }

    @Test
    void datesByTheCountryAndTheColumn() {
        assertThat(WrittenValues.date("11/22/2026", Style.UNKNOWN)).contains(LocalDate.of(2026, 11, 22));
        assertThat(WrittenValues.date("03/04/2026", US)).contains(LocalDate.of(2026, 3, 4));
        assertThat(WrittenValues.date("03/04/2026", GB)).contains(LocalDate.of(2026, 4, 3));
        assertThat(WrittenValues.date("03/04/2026", RU)).isEmpty();
        assertThat(WrittenValues.date("03.04.2026", US)).contains(LocalDate.of(2026, 4, 3));
        assertThat(WrittenValues.date("11/22/26", US)).contains(LocalDate.of(2026, 11, 22));
        assertThat(WrittenValues.date("02/30/2026", US)).isEmpty();
        assertThat(WrittenValues.date("2026-11-22T10:00:00Z", RU)).contains(LocalDate.of(2026, 11, 22));
        for (String written : List.of("Nov 22, 2026", "November 22, 2026", "22 Nov 2026", "22-Nov-2026")) {
            assertThat(WrittenValues.date(written, RU)).as(written).contains(LocalDate.of(2026, 11, 22));
        }
        assertThat(WrittenValues.date("Feb 30, 2026", US)).isEmpty();
        Style american = WrittenValues.dates(List.of("11/22/2026", "03/04/2026"), RU);
        assertThat(WrittenValues.date("03/04/2026", american)).contains(LocalDate.of(2026, 3, 4));
    }

    @Test
    void anyCountryTextGivesAStyle() {
        assertThat(WrittenValues.of(" us ")).isEqualTo(US);
        assertThat(WrittenValues.of(null)).isEqualTo(Style.UNKNOWN);
        assertThat(WrittenValues.of("x".repeat(200))).isEqualTo(Style.UNKNOWN);
    }
}
