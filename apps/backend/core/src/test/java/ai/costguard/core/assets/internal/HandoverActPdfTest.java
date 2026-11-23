package ai.costguard.core.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.org.EmployeeDirectory.EmployeeCard;
import ai.costguard.core.shared.names.Language;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The handover act in the company's language (ADR 0074); the Russian act stays as it was. */
class HandoverActPdfTest {

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static HandoverActPdf.ActData act(String company, String currency, String employee, Language language) {
        return new HandoverActPdf.ActData("2026-7", LocalDate.of(2026, 11, 23), company, currency,
                new EmployeeCard(UUID.randomUUID(), employee, "Analyst", "Engineering", "e@x.example", null),
                "ThinkPad T14", AssetType.LAPTOP, "INV-0001", "PF-123", new BigDecimal("1400.00"),
                LocalDate.of(2027, 11, 23), "alex.morgan@brightline.example", UUID.randomUUID(),
                Instant.parse("2026-11-23T15:00:00Z"), language);
    }

    @Test
    void anEnglishCompanyGetsAnEnglishAct() throws Exception {
        String text = text(HandoverActPdf.render(act("Brightline Analytics Inc.", "USD", "Emily Carter",
                Language.EN)));

        assertThat(text).contains("EQUIPMENT HANDOVER RECEIPT No. 2026-7", "November 23, 2026",
                "Employer: Brightline Analytics Inc.", "Employee: Emily Carter", "Laptop", "1,400.00",
                "Value, USD", "Return by: November 23, 2027", "Received (the Employee)", "Emily Carter /");
        assertThat(text).doesNotContainPattern("[А-Яа-яЁё]");
    }

    @Test
    void aRussianCompanyKeepsItsAct() throws Exception {
        String text = text(HandoverActPdf.render(act("ООО «Ромашка»", "RUB", "Иванов Иван Иванович",
                Language.RU)));

        assertThat(text).contains("АКТ № 2026-7", "23 ноября 2026 г.", "Ноутбук", "1 400,00", "Иванов И. И.");
    }
}
