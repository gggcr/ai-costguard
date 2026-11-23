package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.shared.names.Language;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The signed copy of a PDF speaks the company's language (ADR 0068, ADR 0074). */
class SignatureStampTest {

    private static final Instant SIGNED = Instant.parse("2026-11-23T19:05:00Z");

    private static byte[] blank() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            return new PDFTextStripper().getText(doc);
        }
    }

    private static List<DocumentSignature> signatures(String name) {
        return List.of(new DocumentSignature("employee", name, "emily.carter@brightline.example", "totp",
                "ab".repeat(32), SIGNED));
    }

    @Test
    void anEnglishCompanyGetsAnEnglishStamp() throws Exception {
        String text = text(SignatureStamp.stamp(blank(), "Offer letter", UUID.randomUUID(),
                signatures("Emily Carter"), ZoneId.of("America/New_York"), Language.EN));

        assertThat(text).contains("Signed electronically: Emily Carter (the employee) Nov 23, 2026 14:05 EST",
                "Electronic signature sheet", "Document: Offer letter", "Party: The employee",
                "Verified by: a one-time code from an authenticator app", "SHA-256 of the original file:",
                "depends on the law that applies to it.");
        assertThat(text).doesNotContain("…");
        assertThat(text).doesNotContainPattern("[А-Яа-яЁё]");
    }

    @Test
    void aRussianCompanyKeepsItsStamp() throws Exception {
        String text = text(SignatureStamp.stamp(blank(), "Трудовой договор", UUID.randomUUID(),
                signatures("Иван Иванов"), ZoneId.of("Europe/Moscow")));

        assertThat(text).contains("Подписано электронной подписью: Иван Иванов (работник) 23.11.2026 22:05 МСК",
                "Лист электронных подписей", "Сторона: Работник", "63-ФЗ");
        assertThat(text).doesNotContain("…");
    }
}
