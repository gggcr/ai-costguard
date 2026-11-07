package ai.costguard.core.imports;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What the ingest pipeline reports back about a file. */
public interface ImportResults {

    /** The file was read. Columns and a suggested mapping go to people; unsure values become review tasks. */
    void parsed(UUID tenantId, UUID fileId, List<String> columns, java.util.Map<String, String> suggestedMapping,
            int rows, BigDecimal total, List<RowError> errors, List<Uncertain> uncertain);

    void failed(UUID tenantId, UUID fileId, String reason);

    record RowError(int row, String message) {
    }

    record Uncertain(int row, String field, String value, double confidence) {
    }
}
