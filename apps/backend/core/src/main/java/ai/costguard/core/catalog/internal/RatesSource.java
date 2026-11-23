package ai.costguard.core.catalog.internal;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where the rates come from, one per deployment (ADR 0075): {@code costguard.catalog.rates.source}, {@code cbr} (the
 * Bank of Russia, roubles per unit, the default) or {@code ecb} (the European Central Bank, euros per unit). A rate is
 * the price of one unit of a currency in the source's base currency; the base itself is worth one.
 */
enum RatesSource {
    CBR("RUB", URI.create("https://www.cbr.ru/scripts/XML_daily.asp"), CbrRatesParser::parse),
    ECB("EUR", URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"), EcbRatesParser::parse);

    final String base;
    final URI defaultUrl;
    private final Function<byte[], List<FxRate>> parser;

    RatesSource(String base, URI defaultUrl, Function<byte[], List<FxRate>> parser) {
        this.base = base;
        this.defaultUrl = defaultUrl;
        this.parser = parser;
    }

    List<FxRate> parse(byte[] document) {
        return parser.apply(document);
    }

    static RatesSource of(String name) {
        return name == null || name.isBlank() ? CBR : valueOf(name.strip().toUpperCase(Locale.ROOT));
    }
}
