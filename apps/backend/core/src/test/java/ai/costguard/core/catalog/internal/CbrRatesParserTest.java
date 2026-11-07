package ai.costguard.core.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CbrRatesParserTest {

    /** The real document is windows-1251 and uses a decimal comma. */
    private static final String SAMPLE = """
            <?xml version="1.0" encoding="windows-1251"?>
            <ValCurs Date="07.11.2026" name="Foreign Currency Market">
              <Valute ID="R01235"><NumCode>840</NumCode><CharCode>USD</CharCode><Nominal>1</Nominal>
                <Name>Доллар США</Name><Value>81,2500</Value></Valute>
              <Valute ID="R01375"><NumCode>156</NumCode><CharCode>CNY</CharCode><Nominal>10</Nominal>
                <Name>Юань</Name><Value>112,3400</Value></Valute>
            </ValCurs>
            """;

    @Test
    void readsRatesPerOneUnit() {
        List<FxRate> rates = CbrRatesParser.parse(SAMPLE.getBytes(Charset.forName("windows-1251")));

        assertThat(rates).containsExactly(
                new FxRate(LocalDate.of(2026, 11, 7), "USD", new java.math.BigDecimal("81.25")),
                new FxRate(LocalDate.of(2026, 11, 7), "CNY", new java.math.BigDecimal("11.234")));
    }

    @Test
    void refusesExternalEntities() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]>"
                + "<ValCurs Date=\"07.11.2026\">&e;</ValCurs>";

        assertThatThrownBy(() -> CbrRatesParser.parse(evil.getBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
