package ai.costguard.core.imports.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

enum ImportType { excel, csv, pdf, scan, bank;

    static Optional<ImportType> of(String fileName) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "xlsx", "xls" -> Optional.of(excel);
            case "csv" -> Optional.of(csv);
            case "pdf" -> Optional.of(pdf);
            // the images the ingest pipeline reads through OCR (costguard_ingest.tables.IMAGES)
            case "jpg", "jpeg", "png", "heic", "tif", "tiff", "webp", "bmp" -> Optional.of(scan);
            // 1C exchange and MT940 are text; CAMT.053 is XML; OFX and QFX are OFX statements
            case "txt", "sta", "mt940", "940", "1c", "xml", "ofx", "qfx" -> Optional.of(bank);
            default -> Optional.empty();
        };
    }
}

enum ImportStatus { uploaded, mapping, review, done, failed }

/** The FOCUS fields a column can map to; FOCUS_FIELDS in the frontend shows the same list in Russian. */
final class FocusFields {

    static final Set<String> ALL = Set.of("vendor", "product", "employee", "email", "amount", "currency",
            "period_start", "period_end", "last_active", "department", "skip");

    private FocusFields() {
    }
}

record RowError(int row, String message) {
}

record ReviewTask(UUID id, int row, String field, String value, BigDecimal confidence, boolean resolved,
        String resolvedValue) {
}

record ImportJob(UUID id, String fileName, ImportType type, UUID vendorId, Instant uploadedAt, String uploadedBy,
        ImportStatus status, int rows, BigDecimal total, List<String> columns, Map<String, String> mapping,
        List<RowError> errors, List<ReviewTask> reviewTasks, boolean templateSaved, String failure, String sha256) {
}

/** {@code saveTemplate} may be left out. */
record MappingRequest(@NotNull Map<String, String> mapping, Boolean saveTemplate) {

    boolean keepTemplate() {
        return Boolean.TRUE.equals(saveTemplate);
    }
}

record Resolution(@NotBlank String value) {
}
