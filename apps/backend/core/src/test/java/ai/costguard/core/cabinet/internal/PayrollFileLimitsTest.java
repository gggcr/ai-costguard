package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Finding of 2026-11-13: any employee uploads an .xlsx of their purchases (/me/shadow-spend/imports), and the parser
 * unpacked every sheet into the heap. A file under 1 MB became ~1 GB; a cell far to the right padded its row with
 * billions of empty cells. Such a file is now refused before it takes the memory.
 */
class PayrollFileLimitsTest {

    private static byte[] zip(List<String> names, byte[] eachContent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(eachContent);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] sheet(String cells) {
        return ("<worksheet><sheetData>" + cells + "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aZipBombOfManySheetsIsRefused() throws IOException {
        // 47 sheets of 20 MB of zeros pack into less than 1 MB, the upload limit of a file
        byte[] zeros = new byte[PayrollFile.MAX_PART];
        java.util.Arrays.fill(zeros, (byte) '0');
        byte[] bomb = zip(java.util.stream.IntStream.rangeClosed(1, 47)
                .mapToObj(i -> "xl/worksheets/sheet" + (48 - i) + ".xml").toList(), zeros);
        assertThat(bomb.length).isLessThan(1024 * 1024);

        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        assertThatThrownBy(() -> PayrollFile.table("траты.xlsx", bomb)).hasMessageContaining("readable");
        // at most a few parts were held at once, not 940 MB
        assertThat(rt.totalMemory() - rt.freeMemory() - before).isLessThan(400L * 1024 * 1024);
    }

    @Test
    void aPartLargerThanTheLimitIsRefusedNotCut() throws IOException {
        byte[] big = new byte[PayrollFile.MAX_PART + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        assertThatThrownBy(() -> PayrollFile.table("x.xlsx", zip(List.of("xl/worksheets/sheet1.xml"), big)))
                .hasMessageContaining("readable");
    }

    @Test
    void aCellFarToTheRightIsRefused() throws IOException {
        byte[] far = zip(List.of("xl/worksheets/sheet1.xml"),
                sheet("<row><c r=\"AAAAAAAAAA1\"><v>1</v></c></row>"));
        assertThatThrownBy(() -> PayrollFile.table("x.xlsx", far)).hasMessageContaining("readable");
        byte[] pastXfd = zip(List.of("xl/worksheets/sheet1.xml"), sheet("<row><c r=\"XFE1\"><v>1</v></c></row>"));
        assertThatThrownBy(() -> PayrollFile.table("x.xlsx", pastXfd)).hasMessageContaining("readable");
    }

    @Test
    void aCsvOfMillionsOfEmptyCellsOrAHugeFileIsRefused() {
        byte[] commas = new byte[3_000_000];
        java.util.Arrays.fill(commas, (byte) ',');
        assertThatThrownBy(() -> PayrollFile.table("x.csv", commas)).hasMessageContaining("readable");
        // 4 million rows of one cell, 8 MB: under the size limit, over the cells
        byte[] lines = "a\n".repeat(4_000_000).getBytes(StandardCharsets.UTF_8);
        assertThat(lines.length).isLessThan(PayrollFile.MAX_FILE);
        assertThatThrownBy(() -> PayrollFile.table("x.csv", lines)).hasMessageContaining("readable");
        assertThatThrownBy(() -> PayrollFile.table("x.csv", new byte[PayrollFile.MAX_FILE + 1]))
                .hasMessageContaining("readable");
        assertThat(PayrollFile.table("x.csv", "a;b\n1;2".getBytes(StandardCharsets.UTF_8))).hasSize(2);
    }

    @Test
    void theFirstSheetIsReadWhateverTheOrderOfTheEntries() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet2.xml"));
            zip.write(sheet("<row><c r=\"A1\"><v>2</v></c></row>"));
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheet("<row><c r=\"A1\"><v>1</v></c><c r=\"XFD1\"><v>9</v></c></row>"));
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet3.xml"));
            zip.write(sheet("<row><c r=\"A1\"><v>3</v></c></row>"));
        }
        List<List<String>> rows = PayrollFile.table("x.xlsx", out.toByteArray());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0)).isEqualTo("1");
        assertThat(rows.get(0)).hasSize(PayrollFile.MAX_COLUMN + 1).last().isEqualTo("9");
    }
}
