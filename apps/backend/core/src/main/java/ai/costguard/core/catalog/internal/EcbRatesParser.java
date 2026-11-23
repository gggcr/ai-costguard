package ai.costguard.core.catalog.internal;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml (ADR 0075). The bank gives units of a currency
 * for one euro; CostGuard keeps euros for one unit, so every rate is inverted, and the euro itself is worth one.
 */
final class EcbRatesParser {

    private EcbRatesParser() {
    }

    static List<FxRate> parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList cubes = doc.getElementsByTagName("Cube");
            LocalDate date = null;
            List<FxRate> rates = new ArrayList<>();
            for (int i = 0; i < cubes.getLength(); i++) {
                Element cube = (Element) cubes.item(i);
                if (cube.hasAttribute("time")) {
                    date = LocalDate.parse(cube.getAttribute("time"));
                } else if (cube.hasAttribute("currency") && date != null) {
                    BigDecimal perEuro = new BigDecimal(cube.getAttribute("rate"));
                    if (perEuro.signum() > 0) {
                        rates.add(new FxRate(date, cube.getAttribute("currency").strip(),
                                BigDecimal.ONE.divide(perEuro, MathContext.DECIMAL64).stripTrailingZeros()));
                    }
                }
            }
            if (date == null || rates.isEmpty()) {
                throw new IllegalArgumentException("no rates");
            }
            rates.add(new FxRate(date, "EUR", BigDecimal.ONE));
            return rates;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a European Central Bank daily rates document", e);
        }
    }
}
