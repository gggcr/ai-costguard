package ai.costguard.core.cabinet.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

enum PayKind { advance, salary, vacation_pay, bonus, other }

record PayrollSchedule(@Min(1) @Max(31) int advanceDay, @Min(1) @Max(31) int salaryDay) {
}

/** A pay date ahead: the day of the schedule moved to the last working day before a weekend (ТК РФ, ст. 136). */
record PayDate(String kind, LocalDate date) {
}

record Payment(UUID id, UUID employeeId, String employeeName, String kind, LocalDate period, LocalDate payDate,
        BigDecimal amount, String currency, String source, Instant createdAt) {
}

record MyPayroll(PayrollSchedule schedule, List<PayDate> upcoming, List<Payment> payments) {
}

/**
 * A payment as a source reports it: the employee by e-mail or by full name, {@code period} the month it is for
 * (any day of it), {@code externalKey} the source's own id, kept for tracing.
 */
record ReportedPayment(@Size(max = 320) String employeeEmail, @Size(max = 300) String employeeName,
        @NotNull PayKind kind, @NotNull LocalDate period, @NotNull LocalDate payDate,
        @NotNull @DecimalMin("0") BigDecimal amount, @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 300) String externalKey) {
}

record PaymentBatch(@NotNull @Size(max = 64) String source, @NotEmpty @Size(max = 10000) List<@Valid ReportedPayment> payments) {
}

/** What a batch or a file did: rows stored, and rows left out with the reason, by their line or position. */
record PayrollImport(int stored, List<Skipped> skipped) {
    record Skipped(int row, String reason) {
    }
}
