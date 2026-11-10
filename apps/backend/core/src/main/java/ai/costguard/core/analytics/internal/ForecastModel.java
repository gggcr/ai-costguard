package ai.costguard.core.analytics.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the forecast job publishes (ADR 0034). Months are "2026-12". Amounts are in {@code currency}, the currency of
 * the company. The departments add up to the company: they are reconciled.
 *
 * @param generatedBy the job and its version, for the history
 */
record ForecastDocument(
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @Min(1) @Max(36) int horizon,
        @NotNull @Valid ForecastSeries company,
        @NotNull @Size(max = 500) List<@Valid ForecastSeries> departments,
        @NotNull @Size(max = 36) List<@Valid SavingsMonth> savings,
        @NotBlank @Size(max = 200) String generatedBy) {
}

/**
 * One series: the company, or a department. {@code departmentId} is null for the company and for spend with no
 * department; {@code model} is the champion of the backtest, {@code challenger} the next best, when there was a
 * backtest. {@code baseline} of a point is what the registry says for that month.
 */
record ForecastSeries(
        UUID departmentId,
        @NotNull @Size(max = 200) String name,
        @NotBlank @Size(max = 40) String model,
        BigDecimal wape,
        BigDecimal mase,
        @Size(max = 40) String challenger,
        BigDecimal challengerWape,
        @NotNull @Size(max = 120) List<@Valid MonthAmount> history,
        @NotNull @Size(max = 36) List<@Valid ForecastPoint> points) {
}

record MonthAmount(@NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String month, @NotNull BigDecimal amount) {
}

record ForecastPoint(
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String month,
        @NotNull BigDecimal value,
        @NotNull BigDecimal low,
        @NotNull BigDecimal high,
        BigDecimal baseline) {
}

/** The savings the findings are expected to bring that month, and the part already confirmed. */
record SavingsMonth(
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String month,
        @NotNull BigDecimal expected,
        @NotNull BigDecimal confirmed) {
}

record PublishedForecast(UUID id, Instant publishedAt, ForecastDocument forecast) {
}
