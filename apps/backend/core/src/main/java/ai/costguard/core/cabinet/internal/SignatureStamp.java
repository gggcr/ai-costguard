package ai.costguard.core.cabinet.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;

/**
 * The signed copy of a PDF (ADR 0068): every page gets a line «Подписано электронной подписью» at the top, and a last
 * page lists each signature in a stamp: who, as which side, when, how, and the SHA-256 of the original file. The
 * original stays in storage as it was uploaded; this copy is drawn on every download. The stamp speaks the company's
 * language (ADR 0074); the English one claims no particular law, since which law applies depends on the document.
 */
final class SignatureStamp {

    private static final String FONT = "/fonts/DejaVuSans.ttf";
    /** The moment of a signature in the company's zone (ADR 0073): МСК for Moscow, as before, else the zone. */
    static DateTimeFormatter moment(ZoneId zone) {
        String pattern = zone.equals(ZoneId.of("Europe/Moscow")) ? "dd.MM.yyyy HH:mm 'МСК'" : "dd.MM.yyyy HH:mm z";
        return DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH).withZone(zone);
    }

    /** As an English reader writes it: Nov 23, 2026 14:05 EST. */
    static DateTimeFormatter moment(ZoneId zone, ai.costguard.core.shared.names.Language lang) {
        return lang == ai.costguard.core.shared.names.Language.EN
                ? DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", java.util.Locale.ENGLISH).withZone(zone)
                : moment(zone);
    }
    private static final PDColor BLUE = new PDColor(new float[] {0.11f, 0.30f, 0.62f}, PDDeviceRGB.INSTANCE);
    private static final float MARGIN = 56;

    private SignatureStamp() {
    }

    /** The party in English, for notices of an English company (ADR 0074). */
    static String partyEn(String party) {
        return switch (party) {
            case "employee" -> "The employee";
            case "employer" -> "The employer";
            case "accountant" -> "Accounting";
            default -> party;
        };
    }

    static String party(String party) {
        return switch (party) {
            case "employee" -> "Работник";
            case "employer" -> "Работодатель";
            case "accountant" -> "Бухгалтерия";
            default -> party;
        };
    }

    private static String method(String method, ai.costguard.core.shared.names.Language lang) {
        return "totp".equals(method)
                ? lang.pick("одноразовый код из приложения-аутентификатора", "a one-time code from an authenticator app")
                : lang.pick("ПИН-код подписи", "a signing PIN");
    }

    private static String party(String party, ai.costguard.core.shared.names.Language lang) {
        return lang == ai.costguard.core.shared.names.Language.EN ? partyEn(party) : party(party);
    }

    static byte[] stamp(byte[] pdf, String title, UUID documentId, List<DocumentSignature> signatures,
            ZoneId zone) {
        return stamp(pdf, title, documentId, signatures, zone, ai.costguard.core.shared.names.Language.RU);
    }

    static byte[] stamp(byte[] pdf, String title, UUID documentId, List<DocumentSignature> signatures,
            ZoneId zone, ai.costguard.core.shared.names.Language lang) {
        DateTimeFormatter moment = moment(zone, lang);
        try (PDDocument doc = Loader.loadPDF(pdf); InputStream fontFile = SignatureStamp.class.getResourceAsStream(FONT)) {
            PDType0Font font = PDType0Font.load(doc, fontFile);
            String line = lang.pick("Подписано электронной подписью: ", "Signed electronically: ")
                    + String.join("; ", signatures.stream()
                    .map(s -> s.signerName() + " (" + party(s.party(), lang).toLowerCase() + ") "
                            + moment.format(s.signedAt()))
                    .toList());
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream c = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    c.setNonStrokingColor(BLUE);
                    c.beginText();
                    c.setFont(font, 7);
                    c.newLineAtOffset(box.getLowerLeftX() + 20, box.getUpperRightY() - 14);
                    c.showText(fit(font, 7, line, box.getWidth() - 40));
                    c.endText();
                }
            }
            PDPage sheet = new PDPage(PDRectangle.A4);
            doc.addPage(sheet);
            float width = PDRectangle.A4.getWidth() - 2 * MARGIN;
            try (PDPageContentStream c = new PDPageContentStream(doc, sheet)) {
                float y = PDRectangle.A4.getHeight() - MARGIN;
                y = text(c, font, 14, MARGIN, y, lang.pick("Лист электронных подписей", "Electronic signature sheet"));
                y = text(c, font, 10, MARGIN, y - 4, fit(font, 10, lang.pick("Документ: ", "Document: ") + title,
                        width));
                y = text(c, font, 8, MARGIN, y, lang.pick("Идентификатор документа: ", "Document ID: ") + documentId);
                y -= 12;
                for (DocumentSignature s : signatures) {
                    float h = 92;
                    c.setStrokingColor(BLUE);
                    c.setLineWidth(1.5f);
                    c.addRect(MARGIN, y - h, width, h);
                    c.stroke();
                    c.setNonStrokingColor(BLUE);
                    float ty = text(c, font, 11, MARGIN + 10, y - 8, lang.pick(
                            "ДОКУМЕНТ ПОДПИСАН ПРОСТОЙ ЭЛЕКТРОННОЙ ПОДПИСЬЮ", "SIGNED WITH AN ELECTRONIC SIGNATURE"));
                    c.setNonStrokingColor(0f, 0f, 0f);
                    ty = text(c, font, 9, MARGIN + 10, ty, lang.pick("Сторона: ", "Party: ") + party(s.party(), lang));
                    ty = text(c, font, 9, MARGIN + 10, ty, fit(font, 9, lang.pick("Подписант: ", "Signer: ")
                            + s.signerName() + ", " + s.signerEmail(), width - 20));
                    ty = text(c, font, 9, MARGIN + 10, ty, lang.pick("Дата и время: ", "Date and time: ")
                            + moment.format(s.signedAt()));
                    ty = text(c, font, 9, MARGIN + 10, ty, lang.pick("Способ подтверждения: ", "Verified by: ")
                            + method(s.method(), lang));
                    text(c, font, 7, MARGIN + 10, ty, lang.pick("SHA-256 исходного файла: ",
                            "SHA-256 of the original file: ") + s.documentSha256());
                    y -= h + 12;
                    if (y < MARGIN + 100) {
                        break;
                    }
                }
                c.setNonStrokingColor(0.4f, 0.4f, 0.4f);
                if (lang == ai.costguard.core.shared.names.Language.EN) {
                    // two lines: the note must not be cut
                    text(c, font, 7, MARGIN, MARGIN + 10, fit(font, 7, "An electronic signature made in CostGuard. "
                            + "The hash lets anyone check that this exact file was signed.", width));
                    text(c, font, 7, MARGIN, MARGIN, fit(font, 7, "Whether it suffices for a document depends on the "
                            + "law that applies to it.", width));
                } else {
                    text(c, font, 7, MARGIN, MARGIN, fit(font, 7, "Простая электронная подпись (63-ФЗ, ст. 5, 9) в "
                            + "системе CostGuard. Хэш позволяет проверить, что подписан именно этот файл.", width));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    /** Draws one line with its top at {@code top}; returns where the next line starts. */
    private static float text(PDPageContentStream c, PDType0Font font, float size, float x, float top, String s)
            throws IOException {
        float baseline = top - size;
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, baseline);
        c.showText(s);
        c.endText();
        return baseline - size * 0.6f;
    }

    private static String fit(PDType0Font font, float size, String s, float width) throws IOException {
        if (font.getStringWidth(s) / 1000 * size <= width) {
            return s;
        }
        String cut = s;
        while (cut.length() > 1 && font.getStringWidth(cut + "…") / 1000 * size > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
