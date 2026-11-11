package ai.costguard.core.cabinet.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

enum VacationKind { annual, unpaid, study, other }

enum VacationStatus { pending, approved, rejected, cancelled }

record NewVacation(@NotNull VacationKind kind, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
        @Size(max = 1000) String comment) {
}

record VacationDecision(boolean approve, @Size(max = 1000) String comment) {
}

/** {@code employeeName} is filled for a head or the employer; the employee's own list leaves it empty. */
record Vacation(UUID id, UUID employeeId, String employeeName, String kind, LocalDate startsOn, LocalDate endsOn,
        int days, String comment, String status, String decidedBy, Instant decidedAt, String decisionComment,
        Instant createdAt) {
}

/**
 * Annual paid leave of the calendar year (ТК РФ, ст. 115: 28 calendar days): {@code used} approved, {@code pending}
 * asked for, {@code left} what remains for a new request.
 */
record VacationBalance(int year, int daysPerYear, int used, int pending, int left, List<Vacation> requests,
        boolean hasHead) {
}
