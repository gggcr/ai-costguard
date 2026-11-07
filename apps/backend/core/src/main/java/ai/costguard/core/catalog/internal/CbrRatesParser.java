package ai.costguard.core.catalog.internal;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads https://www.cbr.ru/scripts/XML_daily.asp. A rate is given for {@code Nominal} units with a decimal comma,
 * for example 10 Chinese yuan, so it is divided down to one unit.
 */
final class CbrRatesParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private CbrRatesParser() {
    }

    static List<FxRate> parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            LocalDate date = LocalDate.parse(doc.getDocumentElement().getAttribute("Date"), DATE);
            NodeList valutes = doc.getElementsByTagName("Valute");
            List<FxRate> rates = new ArrayList<>();
            for (int i = 0; i < valutes.getLength(); i++) {
                Element v = (Element) valutes.item(i);
                String code = text(v, "CharCode");
                BigDecimal nominal = new BigDecimal(text(v, "Nominal"));
                BigDecimal value = new BigDecimal(text(v, "Value").replace(',', '.'));
                rates.add(new FxRate(date, code, value.divide(nominal, MathContext.DECIMAL64).stripTrailingZeros()));
            }
            return rates;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a Bank of Russia daily rates document", e);
        }
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent().strip();
    }
}
