package ai.costguard.core.shared.inbox;

import ai.costguard.core.imports.ImportResults;
import ai.costguard.events.IngestResult;
import ai.costguard.events.IngestStatus;
import ai.costguard.platform.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands what the ingest pipeline found to the imports module. A result for a file the core does not know, or one
 * that already moved on, is logged and skipped: retrying it would never succeed. Anything else (the database is
 * down) is thrown, so the offset is not committed and the message comes again.
 */
class ImportResultInbox {

    private static final Logger log = LoggerFactory.getLogger(ImportResultInbox.class);

    private final ImportResults imports;

    ImportResultInbox(ImportResults imports) {
        this.imports = imports;
    }

    void handle(IngestResult r) {
        try {
            if (r.getStatus() == IngestStatus.FAILED) {
                imports.failed(r.getTenantId(), r.getFileId(), r.getFailure() == null ? "unknown" : r.getFailure());
                return;
            }
            imports.parsed(r.getTenantId(), r.getFileId(), r.getColumns(), r.getSuggestedMapping(), r.getRows(),
                    r.getTotal(), r.getErrors().stream().map(e -> new ImportResults.RowError(e.getRow(), e.getMessage()))
                            .toList(),
                    r.getUncertain().stream().map(u -> new ImportResults.Uncertain(u.getRow(), u.getField(),
                            u.getValue(), u.getConfidence())).toList());
        } catch (ApiException e) {
            log.warn("Ingest result for file {} of tenant {} skipped: {}", r.getFileId(), r.getTenantId(),
                    e.getMessage());
        }
    }
}
