package ai.costguard.core.privacy.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum StepAction { deleted, anonymized, retained, manual }

/**
 * One line of an erasure record. {@code note} explains a retained or a manual step; {@code until} is the day
 * retention removes what was retained.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Step(String store, StepAction action, long rows, String note,
        @Schema(format = "date") String until) {
}

enum ErasureReason { request, retention }

/** What was erased of an employee and what was kept. It names nobody. */
record Erasure(UUID id, UUID employeeId, ErasureReason reason, Instant erasedAt, List<Step> steps) {
}

enum CompanyErasureStatus { scheduled, cancelled, completed }

record CompanyErasure(CompanyErasureStatus status, Instant requestedAt, Instant scheduledFor, Instant cancelledAt,
        Instant completedAt, int attempts, String lastError, List<Step> steps) {
}

/** The owner types the company name, as it is saved, to confirm. */
record CompanyErasureRequest(@NotBlank String confirmName) {
}
