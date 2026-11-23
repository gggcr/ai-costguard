package ai.costguard.core.assets.internal;

import ai.costguard.core.org.EmployeeDirectory.EmployeeCard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The handover act as a one-page A4 PDF in Russian. DejaVu Sans is embedded as a subset, because the standard
 * PDF fonts have no Cyrillic. Nothing here touches the database: the caller gives every value the act shows.
 */
final class HandoverActPdf {

    /** What the act shows. {@code company} and {@code employee} are as they were at the moment of the issue. */
    record ActData(String number, LocalDate issuedOn, String company, String currency, EmployeeCard employee,
            String model, AssetType type, String inventoryNumber, String serialNumber, BigDecimal cost,
            LocalDate returnDue, String issuedBy, UUID assignmentId, Instant createdAt, ai.costguard.core.shared.names.Language language) {

        ActData(String number, LocalDate issuedOn, String company, String currency, EmployeeCard employee,
                String model, AssetType type, String inventoryNumber, String serialNumber, BigDecimal cost,
                LocalDate returnDue, String issuedBy, UUID assignmentId, Instant createdAt) {
            this(number, issuedOn, company, currency, employee, model, type, inventoryNumber, serialNumber, cost,
                    returnDue, issuedBy, assignmentId, createdAt, ai.costguard.core.shared.names.Language.RU);
        }

        boolean english() {
            return language == ai.costguard.core.shared.names.Language.EN;
        }
    }

    private static final DateTimeFormatter DAY_EN = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private static final String FONT = "/fonts/DejaVuSans.ttf";
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", RU);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'")
            .withZone(ZoneId.of("UTC"));
    private static final String BLANK = "______________________________";

    private static final float MARGIN = 56;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float[] COLUMNS = {22, 150, 60, 80, 96, 75};

    private HandoverActPdf() {
    }

    static byte[] render(ActData d) {
        try (PDDocument doc = new PDDocument(); InputStream fontFile = HandoverActPdf.class.getResourceAsStream(FONT)) {
            if (fontFile == null) {
                throw new IllegalStateException("Font " + FONT + " is missing from the classpath");
            }
            PDType0Font font = PDType0Font.load(doc, fontFile, true);
            info(doc, d);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                Canvas canvas = new Canvas(out, font, PDRectangle.A4.getHeight() - MARGIN);
                if (d.english()) {
                    writeEnglish(canvas, d);
                } else {
                    write(canvas, d);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the handover act", e);
        }
    }

    private static void info(PDDocument doc, ActData d) {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(d.english() ? "Equipment handover receipt No. " + d.number()
                : "Акт № " + d.number() + " приёма-передачи оборудования");
        info.setSubject(d.model() + (d.english() ? ", asset tag " : ", инв. № ") + d.inventoryNumber());
        info.setCreator("CostGuard");
        info.setProducer("CostGuard");
        Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        created.setTimeInMillis(d.createdAt().toEpochMilli());
        info.setCreationDate(created);
    }

    private static void write(Canvas c, ActData d) throws IOException {
        String company = blankIfMissing(d.company());
        EmployeeCard who = d.employee();

        c.centered("АКТ № " + d.number(), 14);
        c.gap(4);
        c.centered("приёма-передачи оборудования сотруднику", 11);
        c.gap(16);
        c.line(DAY.format(d.issuedOn()), 10.5f);
        c.gap(10);

        c.line("Работодатель: " + company, 10.5f);
        c.line("Сотрудник: " + who.name(), 10.5f);
        c.line("Должность: " + who.position(), 10.5f);
        c.line("Подразделение: " + who.department(), 10.5f);
        c.gap(10);

        c.line(company + " (далее — Работодатель) передаёт, а сотрудник " + who.name()
                + " (далее — Сотрудник) принимает для выполнения трудовых обязанностей следующее оборудование:",
                10.5f);
        c.gap(6);

        c.table(COLUMNS, List.of(
                List.of("№", "Наименование", "Тип", "Инв. №", "Серийный №", "Стоимость, " + currency(d.currency())),
                List.of("1", d.model(), typeName(d.type()), d.inventoryNumber(),
                        d.serialNumber() == null ? "—" : d.serialNumber(), money(d.cost()))), 9);
        c.gap(10);

        c.line(d.returnDue() == null
                ? "Срок возврата не установлен: оборудование возвращается по требованию Работодателя "
                        + "или при увольнении."
                : "Срок возврата: " + DAY.format(d.returnDue()), 10.5f); // the date ends with «г.»
        c.gap(6);
        for (String clause : List.of(
                "1. Оборудование передано в исправном состоянии. Сотрудник проверил его комплектность "
                        + "и работоспособность.",
                "2. Сотрудник обязуется использовать оборудование для выполнения трудовых обязанностей, обеспечить "
                        + "его сохранность и вернуть Работодателю по окончании срока, по требованию "
                        + "или при увольнении.",
                "3. Акт составлен в двух экземплярах, по одному для каждой стороны.")) {
            c.line(clause, 10.5f);
            c.gap(3);
        }
        c.gap(28);

        float half = WIDTH / 2;
        c.columns(half, "Передал (от Работодателя)", "Принял (Сотрудник)", 10.5f);
        c.gap(22);
        boolean reported = d.issuedBy().startsWith(AssetService.CONNECTOR);
        c.columns(half, "______________ / " + (reported ? "________________" : signer(d.issuedBy())) + " /",
                "______________ / " + initials(who.name()) + " /", 10.5f);
        c.gap(8);
        c.columns(half, "«___» ____________ 20___ г.", "«___» ____________ 20___ г.", 10.5f);

        c.footer("Акт сформирован CostGuard автоматически при выдаче " + MOMENT.format(d.createdAt())
                + (reported ? " по данным " + d.issuedBy().substring(AssetService.CONNECTOR.length()) : "")
                + ". Выдача " + d.assignmentId() + ".", 7.5f);
    }

    /**
     * The act of an English company (ADR 0074): the same content as the Russian act, in plain English, with no
     * reference to a particular law. Names are written in full, as an English signature line has them.
     */
    private static void writeEnglish(Canvas c, ActData d) throws IOException {
        String company = blankIfMissing(d.company());
        EmployeeCard who = d.employee();

        c.centered("EQUIPMENT HANDOVER RECEIPT No. " + d.number(), 14);
        c.gap(4);
        c.centered("equipment issued to an employee", 11);
        c.gap(16);
        c.line(DAY_EN.format(d.issuedOn()), 10.5f);
        c.gap(10);

        c.line("Employer: " + company, 10.5f);
        c.line("Employee: " + who.name(), 10.5f);
        c.line("Position: " + who.position(), 10.5f);
        c.line("Department: " + who.department(), 10.5f);
        c.gap(10);

        c.line(company + " (the Employer) hands over, and " + who.name()
                + " (the Employee) receives, the following equipment for the Employee's work:", 10.5f);
        c.gap(6);

        c.table(COLUMNS, List.of(
                List.of("#", "Item", "Type", "Asset tag", "Serial number",
                        "Value, " + (d.currency() == null ? "" : d.currency())),
                List.of("1", d.model(), typeNameEn(d.type()), d.inventoryNumber(),
                        d.serialNumber() == null ? "—" : d.serialNumber(), moneyEn(d.cost()))), 9);
        c.gap(10);

        c.line(d.returnDue() == null
                ? "No return date: the equipment is returned at the Employer's request or when employment ends."
                : "Return by: " + DAY_EN.format(d.returnDue()), 10.5f);
        c.gap(6);
        for (String clause : List.of(
                "1. The equipment was handed over in working order. The Employee checked that it is complete and "
                        + "works.",
                "2. The Employee will use the equipment for work, keep it safe, and return it to the Employer at the "
                        + "end of the term, on request, or when employment ends.",
                "3. This receipt is made in two copies, one for each party.")) {
            c.line(clause, 10.5f);
            c.gap(3);
        }
        c.gap(28);

        float half = WIDTH / 2;
        c.columns(half, "Handed over (for the Employer)", "Received (the Employee)", 10.5f);
        c.gap(22);
        boolean reported = d.issuedBy().startsWith(AssetService.CONNECTOR);
        c.columns(half, "______________ / " + (reported ? "________________" : d.issuedBy()) + " /",
                "______________ / " + who.name().strip() + " /", 10.5f);
        c.gap(8);
        c.columns(half, "Date: ____________", "Date: ____________", 10.5f);

        c.footer("Generated by CostGuard when the equipment was issued, " + MOMENT.format(d.createdAt())
                + (reported ? ", from data of " + d.issuedBy().substring(AssetService.CONNECTOR.length()) : "")
                + ". Issue " + d.assignmentId() + ".", 7.5f);
    }

    static String typeNameEn(AssetType type) {
        return switch (type) {
            case LAPTOP -> "Laptop";
            case PHONE -> "Phone";
            case MONITOR -> "Monitor";
            case OTHER -> "Other";
        };
    }

    static String moneyEn(BigDecimal amount) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(amount);
    }

    static String typeName(AssetType type) {
        return switch (type) {
            case LAPTOP -> "Ноутбук";
            case PHONE -> "Телефон";
            case MONITOR -> "Монитор";
            case OTHER -> "Прочее";
        };
    }

    /** A login or an e-mail is shown as it is; a full name as initials, like the employee's. */
    static String signer(String issuedBy) {
        return issuedBy.contains("@") ? issuedBy : initials(issuedBy);
    }

    /** «Иванов Иван Иванович» → «Иванов И. И.»; a name of one word stays as it is. */
    static String initials(String fullName) {
        String[] parts = fullName.strip().split("\\s+");
        if (parts.length < 2) {
            return fullName.strip();
        }
        return parts[0] + " " + Arrays.stream(parts, 1, parts.length)
                .map(p -> p.substring(0, 1).toUpperCase(RU) + ".").collect(Collectors.joining(" "));
    }

    static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(RU);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", symbols).format(amount);
    }

    private static String currency(String code) {
        return code == null || "RUB".equals(code) ? "руб." : code;
    }

    private static String blankIfMissing(String text) {
        return text == null || text.isBlank() ? BLANK : text.strip();
    }

    /** Writes top to bottom, keeping the current baseline in {@code y}. */
    private static final class Canvas {

        private final PDPageContentStream out;
        private final PDType0Font font;
        private final Map<Integer, Boolean> glyphs = new HashMap<>();
        private float y;

        Canvas(PDPageContentStream out, PDType0Font font, float top) {
            this.out = out;
            this.font = font;
            this.y = top;
        }

        void gap(float points) {
            y -= points;
        }

        /**
         * Text from people and connectors may hold characters the font has no glyph for (emoji, control
         * characters); those become «?» instead of failing the issue.
         */
        String clean(String text) {
            StringBuilder shown = new StringBuilder(text.length());
            text.codePoints().forEach(cp -> {
                if (Character.isWhitespace(cp) || Character.isISOControl(cp)) {
                    shown.append(' ');
                } else if (glyphs.computeIfAbsent(cp, this::encodable)) {
                    shown.appendCodePoint(cp);
                } else {
                    shown.append('?');
                }
            });
            return shown.toString();
        }

        private boolean encodable(int codePoint) {
            try {
                font.encode(new String(Character.toChars(codePoint)));
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }

        float width(String text, float size) throws IOException {
            return font.getStringWidth(clean(text)) / 1000 * size;
        }

        void text(float x, float baseline, String text, float size) throws IOException {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, baseline);
            out.showText(clean(text));
            out.endText();
        }

        void line(String text, float size) throws IOException {
            for (String part : wrap(text, size, WIDTH)) {
                y -= size * 1.35f;
                text(MARGIN, y, part, size);
            }
        }

        void centered(String text, float size) throws IOException {
            y -= size * 1.35f;
            text(MARGIN + (WIDTH - width(text, size)) / 2, y, text, size);
        }

        void columns(float half, String left, String right, float size) throws IOException {
            y -= size * 1.35f;
            text(MARGIN, y, fit(left, size, half - 8), size);
            text(MARGIN + half, y, fit(right, size, half - 8), size);
        }

        void footer(String text, float size) throws IOException {
            float baseline = MARGIN / 2;
            for (String part : wrap(text, size, WIDTH).reversed()) {
                out.setNonStrokingColor(0.4f, 0.4f, 0.4f);
                text(MARGIN, baseline, part, size);
                baseline += size * 1.3f;
            }
            out.setNonStrokingColor(0f, 0f, 0f);
        }

        void table(float[] widths, List<List<String>> rows, float size) throws IOException {
            float leading = size * 1.3f;
            float pad = 4;
            out.setLineWidth(0.6f);
            for (List<String> row : rows) {
                List<List<String>> cells = new ArrayList<>();
                int lines = 1;
                for (int i = 0; i < widths.length; i++) {
                    List<String> wrapped = wrap(row.get(i), size, widths[i] - 2 * pad);
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                float height = lines * leading + 2 * pad;
                float x = MARGIN;
                for (int i = 0; i < widths.length; i++) {
                    out.addRect(x, y - height, widths[i], height);
                    float baseline = y - pad - size;
                    for (String part : cells.get(i)) {
                        text(x + pad, baseline, part, size);
                        baseline -= leading;
                    }
                    x += widths[i];
                }
                out.stroke();
                y -= height;
            }
        }

        /** Breaks at spaces; a word wider than the line is broken by characters. */
        List<String> wrap(String raw, float size, float max) throws IOException {
            String text = clean(raw);
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(candidate, size) <= max) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                String rest = word;
                while (width(rest, size) > max && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(rest.substring(0, cut), size) > max) {
                        cut--;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                current.append(rest);
            }
            if (!current.isEmpty() || lines.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        private String fit(String raw, float size, float max) throws IOException {
            String text = clean(raw);
            String shown = text;
            while (shown.length() > 1 && width(shown, size) > max) {
                shown = shown.substring(0, shown.length() - 1);
            }
            return shown.length() < text.length() ? shown.substring(0, Math.max(1, shown.length() - 1)) + "…" : shown;
        }
    }
}
