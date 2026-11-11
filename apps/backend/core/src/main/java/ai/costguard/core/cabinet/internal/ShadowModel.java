package ai.costguard.core.cabinet.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record NewShadowSpend(@NotBlank @Size(max = 200) String service, @NotNull @DecimalMin("0") BigDecimal amount,
        @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull LocalDate paidOn, @Size(max = 1000) String purpose) {
}

/** A report as its author sees it; nobody else sees single reports. */
record ShadowSpend(UUID id, String service, String category, BigDecimal amount, String currency, LocalDate paidOn,
        String purpose, Instant createdAt) {
}

record ShadowImport(int stored, List<PayrollImport.Skipped> skipped) {
}

/**
 * What management sees (ADR 0057): services at least {@code minPeople} different people paid for themselves, with
 * departments where as many did; services below that are only counted.
 */
record ShadowSummary(int minPeople, List<Service> services, int hiddenServices) {

    record Service(String service, String category, int people, int reports, BigDecimal total, String currency,
            LocalDate lastPaid, List<Department> departments) {
    }

    record Department(String department, int people, BigDecimal total) {
    }
}
