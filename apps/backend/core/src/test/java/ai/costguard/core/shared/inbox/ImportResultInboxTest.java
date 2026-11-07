package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.imports.ImportResults;
import ai.costguard.events.IngestResult;
import ai.costguard.events.IngestRowError;
import ai.costguard.events.IngestStatus;
import ai.costguard.events.IngestUncertainValue;
import ai.costguard.platform.errors.ProblemCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ImportResultInboxTest {

    enum Missing implements ProblemCode {
        FILE;

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String detail() {
            return "Import not found";
        }
    }

    private final List<String> calls = new ArrayList<>();
    private final UUID tenant = UUID.randomUUID();
    private final UUID file = UUID.randomUUID();

    private final ImportResults results = new ImportResults() {
        @Override
        public void parsed(UUID t, UUID f, List<String> columns, Map<String, String> mapping, int rows,
                BigDecimal total, List<RowError> errors, List<Uncertain> uncertain) {
            if (f.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
                throw Missing.FILE.failure();
            }
            calls.add("parsed " + columns + " " + mapping + " " + rows + " " + total + " " + errors + " " + uncertain);
        }

        @Override
        public void failed(UUID t, UUID f, String reason) {
            calls.add("failed " + reason);
        }
    };

    private IngestResult.Builder result() {
        return IngestResult.newBuilder().setTenantId(tenant).setFileId(file).setStatus(IngestStatus.PARSED)
                .setColumns(List.of("Сумма")).setSuggestedMapping(Map.of("Сумма", "amount")).setRows(2)
                .setTotal(new BigDecimal("1500.000000")).setErrors(List.of(new IngestRowError(3, "no date")))
                .setUncertain(List.of(new IngestUncertainValue(2, "amount", "1 5OO", 0.4)))
                .setFailure(null).setReportedAt(Instant.now());
    }

    @Test
    void passesParsedFilesToImports() {
        new ImportResultInbox(results).handle(result().build());

        assertThat(calls).singleElement().asString().startsWith("parsed [Сумма] {Сумма=amount} 2 1500.000000")
                .contains("no date").contains("1 5OO");
    }

    @Test
    void passesFailures() {
        new ImportResultInbox(results).handle(result().setStatus(IngestStatus.FAILED).setFailure("bad zip").build());

        assertThat(calls).containsExactly("failed bad zip");
    }

    @Test
    void skipsFilesTheCoreDoesNotKnow() {
        new ImportResultInbox(results).handle(result().setFileId(new UUID(0, 0)).build());

        assertThat(calls).isEmpty();
    }
}
