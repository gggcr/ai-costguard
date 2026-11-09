package ai.costguard.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImportTypeTest {

    @Test
    void bankStatementsOfEveryFormatAreAccepted() {
        for (String name : new String[] {"выписка.txt", "a.sta", "a.mt940", "a.940", "camt053.XML", "a.ofx",
                "a.qfx"}) {
            assertThat(ImportType.of(name)).as(name).contains(ImportType.bank);
        }
    }

    @Test
    void scansIncludeTiff() {
        assertThat(ImportType.of("scan.tiff")).contains(ImportType.scan);
        assertThat(ImportType.of("scan.tif")).contains(ImportType.scan);
        assertThat(ImportType.of("a.exe")).isEmpty();
    }
}
