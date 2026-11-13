package ai.costguard.core.cabinet.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A pay sheet as finance exports it (ADR 0054): .xlsx or .csv, one payment a row, the header on the first row. The
 * columns are found by their names, in Russian or English:
 * <ul>
 *   <li>сотрудник, ФИО, работник, e-mail, почта — the person: an e-mail, or the full name;</li>
 *   <li>вид, вид выплаты — аванс, зарплата, отпускные, премия, else other;</li>
 *   <li>период, месяц — the month (01.10.2026, 2026-10 or a date of it);</li>
 *   <li>дата выплаты, дата — the day it is paid;</li>
 *   <li>сумма, к выплате — the amount, a comma or a dot.</li>
 * </ul>
 * Only the first sheet of a workbook is read. The core has no spreadsheet library: an .xlsx is a zip of XML, and
 * this reads its shared strings and the first sheet's cells with the JDK.
 */
final class PayrollFile {

    record Row(int line, String person, String kind, String period, String payDate, String amount) {
    }

    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("сотрудник", "person"), Map.entry("фио", "person"), Map.entry("работник", "person"),
            Map.entry("employee", "person"), Map.entry("e-mail", "person"), Map.entry("email", "person"),
            Map.entry("почта", "person"),
            Map.entry("вид", "kind"), Map.entry("вид выплаты", "kind"), Map.entry("kind", "kind"),
            Map.entry("период", "period"), Map.entry("месяц", "period"), Map.entry("period", "period"),
            Map.entry("дата выплаты", "payDate"), Map.entry("дата", "payDate"), Map.entry("pay date", "payDate"),
            Map.entry("сумма", "amount"), Map.entry("к выплате", "amount"), Map.entry("сумма к выплате", "amount"),
            Map.entry("amount", "amount"));

    private PayrollFile() {
    }

    /** Every row of the first sheet of an .xlsx, or of a .csv, as text cells. */
    static List<List<String>> table(String fileName, byte[] content) {
        if (content.length > MAX_FILE) {
            // a pay sheet or a list of purchases is far smaller; uploads take up to 50 MB for scanned documents
            throw CabinetProblem.PAYROLL_FILE.failure();
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? xlsx(content) : csv(content);
    }

    static List<Row> read(String fileName, byte[] content) {
        List<List<String>> table = table(fileName, content);
        if (table.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> at = new HashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) {
            String field = COLUMNS.get(header.get(i).strip().toLowerCase(Locale.ROOT));
            if (field != null) {
                at.putIfAbsent(field, i);
            }
        }
        for (String needed : List.of("person", "payDate", "amount")) {
            if (!at.containsKey(needed)) {
                throw CabinetProblem.PAYROLL_COLUMNS.failure();
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(new Row(r + 1, cell(cells, at.get("person")), cell(cells, at.get("kind")),
                    cell(cells, at.get("period")), cell(cells, at.get("payDate")), cell(cells, at.get("amount"))));
        }
        return rows;
    }

    private static String cell(List<String> cells, Integer i) {
        return i == null || i >= cells.size() ? "" : cells.get(i).strip();
    }

    static PayKind kind(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("аванс") || t.equals("advance")) {
            return PayKind.advance;
        }
        if (t.contains("отпуск") || t.equals("vacation_pay")) {
            return PayKind.vacation_pay;
        }
        if (t.contains("прем") || t.equals("bonus")) {
            return PayKind.bonus;
        }
        if (t.isBlank() || t.contains("зарплат") || t.contains("оклад") || t.contains("заработн") || t.equals("salary")) {
            return PayKind.salary;
        }
        return PayKind.other;
    }

    private static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** 15.10.2026, 2026-10-15, 2026-10 (the first day) or an Excel day number. */
    static LocalDate date(String text) {
        String t = text.strip();
        try {
            if (t.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) {
                String[] p = t.split("\\.");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
            if (t.matches("\\d{4}-\\d{2}")) {
                return LocalDate.parse(t + "-01");
            }
            if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return LocalDate.parse(t.substring(0, 10));
            }
            if (t.matches("\\d+(\\.0+)?")) {
                return LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(t.replaceAll("\\..*", "")));
            }
            return LocalDate.parse(t, RU);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal amount(String text) {
        String t = text.replace("\u00a0", "").replace(" ", "").replace("₽", "").replace(",", ".");
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- CSV: ; or , separated, quotes allowed, UTF-8 with or without BOM ---

    private static List<List<String>> csv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<List<String>> rows = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        char separator = lines.length > 0 && lines[0].contains(";") ? ';' : ',';
        long count = 0;
        for (String line : lines) {
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == separator && !quoted) {
                    if (++count > MAX_CELLS) {
                        throw CabinetProblem.PAYROLL_FILE.failure();
                    }
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            if (++count > MAX_CELLS) {
                throw CabinetProblem.PAYROLL_FILE.failure();
            }
            cells.add(cur.toString());
            rows.add(cells);
        }
        return rows;
    }

    // --- XLSX: shared strings and the first worksheet ---

    /** The largest pay sheet or purchase list taken, as uploaded. */
    static final int MAX_FILE = 10 * 1024 * 1024;
    /** One part of a workbook (shared strings, a sheet) is at most this much XML once unpacked. */
    static final int MAX_PART = 20 * 1024 * 1024;
    /** Everything unpacked from one workbook: a small zip must not unpack into the heap (a zip bomb). */
    static final long MAX_UNPACKED = 48L * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;
    /** XFD, the last column of Excel. */
    static final int MAX_COLUMN = 16_383;
    static final int MAX_CELLS = 2_000_000;

    private static final Pattern SHEET = Pattern.compile("xl/worksheets/sheet(\\d{1,9})\\.xml");

    /**
     * Reads the shared strings and the sheet with the smallest number; every other entry is skipped unread. A part
     * over {@link #MAX_PART}, more than {@link #MAX_UNPACKED} unpacked in all or more than {@link #MAX_ENTRIES}
     * entries is not a pay sheet: 400.
     */
    private static List<List<String>> xlsx(byte[] content) {
        byte[] strings = null;
        byte[] sheet = null;
        long sheetNumber = Long.MAX_VALUE;
        long unpacked = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) {
                    throw CabinetProblem.PAYROLL_FILE.failure();
                }
                var m = SHEET.matcher(e.getName());
                boolean isStrings = e.getName().equals("xl/sharedStrings.xml");
                long number = m.matches() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
                if (!isStrings && number >= sheetNumber) {
                    continue;
                }
                byte[] part = zip.readNBytes(MAX_PART + 1);
                unpacked += part.length;
                if (part.length > MAX_PART || unpacked > MAX_UNPACKED) {
                    throw CabinetProblem.PAYROLL_FILE.failure();
                }
                if (isStrings) {
                    strings = part;
                } else {
                    sheet = part;
                    sheetNumber = number;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw CabinetProblem.PAYROLL_FILE.failure();
        }
        if (sheet == null) {
            throw CabinetProblem.PAYROLL_FILE.failure();
        }
        try {
            return cells(sheet, strings == null ? List.of() : sharedStrings(strings));
        } catch (XMLStreamException | NumberFormatException e) {
            throw CabinetProblem.PAYROLL_FILE.failure();
        }
    }

    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(bytes), "UTF-8");
    }

    private static List<String> sharedStrings(byte[] bytes) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        StringBuilder cur = null;
        boolean inT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("si")) {
                    cur = new StringBuilder();
                } else if (r.getLocalName().equals("t")) {
                    inT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inT && cur != null) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("t")) {
                    inT = false;
                } else if (r.getLocalName().equals("si") && cur != null) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        }
        return out;
    }

    private static List<List<String>> cells(byte[] bytes, List<String> strings) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        List<String> row = null;
        int column = 0;
        long cells = 0;
        String type = null;
        StringBuilder value = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        column = ref == null ? (row == null ? 0 : row.size()) : columnOf(ref);
                        if (column > MAX_COLUMN) {
                            // a cell far to the right would pad the row with millions of empty cells
                            throw CabinetProblem.PAYROLL_FILE.failure();
                        }
                        type = r.getAttributeValue(null, "t");
                        value = new StringBuilder();
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && value != null) {
                value.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("c") && row != null && value != null) {
                    String v = value.toString();
                    if ("s".equals(type) && !v.isBlank()) {
                        int i = Integer.parseInt(v.strip());
                        v = i < strings.size() ? strings.get(i) : "";
                    }
                    cells += Math.max(1, column + 1 - row.size());
                    if (cells > MAX_CELLS) {
                        throw CabinetProblem.PAYROLL_FILE.failure();
                    }
                    while (row.size() < column) {
                        row.add("");
                    }
                    row.add(v);
                    value = null;
                } else if (r.getLocalName().equals("row") && row != null) {
                    rows.add(row);
                    row = null;
                }
            }
        }
        return rows;
    }

    private static int columnOf(String ref) {
        int n = 0;
        for (char c : ref.toCharArray()) {
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                n = n * 26 + (Character.toUpperCase(c) - 'A' + 1);
                if (n > MAX_COLUMN + 1) {
                    return Integer.MAX_VALUE;
                }
            } else {
                break;
            }
        }
        return n - 1;
    }
}
