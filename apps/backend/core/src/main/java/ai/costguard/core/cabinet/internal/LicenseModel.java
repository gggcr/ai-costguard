package ai.costguard.core.cabinet.internal;

import ai.costguard.core.software.LicensePool;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code days}: for a while (ADR 0066): the license goes back when they are over; null: for good. */
record NewLicenseRequest(@NotNull UUID subscriptionId, @Size(max = 1000) String reason,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(90) Integer days) {
}

record LicenseDecision(boolean approve, @Size(max = 1000) String comment) {
}

/** {@code idleSince}: the request would take back a colleague's license unused since then (null: never used). */
record LicenseRequest(UUID id, UUID employeeId, String employeeName, UUID subscriptionId, String product,
        String vendor, String reason, String status, boolean takesIdle, LocalDate idleSince, String decidedBy,
        Instant decidedAt, String decisionComment, Instant createdAt, Integer days) {
}

/** What a person sees of the pool: their licenses, the catalogue with availability, their requests. */
record MyLicenses(List<LicensePool.Held> held, List<LicensePool.Offer> pool, List<LicenseRequest> requests,
        int idleDays, List<LeaseView> borrowed, List<LeaseView> lent) {
}

/** A license held for a while (ADR 0066): {@code fromColleague} when it goes back to a colleague, not the pool. */
record LeaseView(UUID licenseId, String product, LocalDate endsOn, boolean fromColleague) {

    static LeaseView of(Lease l) {
        return new LeaseView(l.licenseId(), l.product(), l.endsOn(), l.lenderId() != null);
    }
}

record Lease(UUID licenseId, UUID subscriptionId, String product, UUID employeeId, String employeeName, UUID lenderId,
        LocalDate endsOn) {
}

record MorningResult(int leasesEnded, int renewalReminders) {
}

record Reclaimed(int licenses) {
}
