package ai.costguard.core.cabinet.internal;

import ai.costguard.core.software.LicensePool;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record NewLicenseRequest(@NotNull UUID subscriptionId, @Size(max = 1000) String reason) {
}

record LicenseDecision(boolean approve, @Size(max = 1000) String comment) {
}

/** {@code idleSince}: the request would take back a colleague's license unused since then (null: never used). */
record LicenseRequest(UUID id, UUID employeeId, String employeeName, UUID subscriptionId, String product,
        String vendor, String reason, String status, boolean takesIdle, LocalDate idleSince, String decidedBy,
        Instant decidedAt, String decisionComment, Instant createdAt) {
}

/** What a person sees of the pool: their licenses, the catalogue with availability, their requests. */
record MyLicenses(List<LicensePool.Held> held, List<LicensePool.Offer> pool, List<LicenseRequest> requests,
        int idleDays) {
}

record Reclaimed(int licenses) {
}
