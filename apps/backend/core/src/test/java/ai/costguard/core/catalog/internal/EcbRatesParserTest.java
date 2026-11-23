package ai.costguard.core.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0075: the ECB gives units for one euro; CostGuard keeps euros for one unit, the euro itself worth one. */
class EcbRatesParserTest {

    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
              <Cube>
                <Cube time="2026-11-20">
                  <Cube currency="USD" rate="1.25"/>
                  <Cube currency="JPY" rate="160.00"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void invertsTheRatesAndAddsTheEuro() {
        List<FxRate> rates = EcbRatesParser.parse(SAMPLE.getBytes(StandardCharsets.UTF_8));
        LocalDate day = LocalDate.of(2026, 11, 20);
        assertThat(rates).containsExactly(new FxRate(day, "USD", new BigDecimal("0.8")),
                new FxRate(day, "JPY", new BigDecimal("0.00625")), new FxRate(day, "EUR", BigDecimal.ONE));
        assertThat(RatesSource.of("ecb").base).isEqualTo("EUR");
        assertThat(RatesSource.of(null).base).isEqualTo("RUB");
    }

    @Test
    void refusesADocumentWithoutRatesOrWithADoctype() {
        assertThatThrownBy(() -> EcbRatesParser.parse("<Cube/>".getBytes())).isInstanceOf(IllegalArgumentException.class);
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Cube>&e;</Cube>";
        assertThatThrownBy(() -> EcbRatesParser.parse(evil.getBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
