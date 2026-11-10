package ai.costguard.core.analytics.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a scenario changes (ADR 0034). {@code subscriptionId} null means every subscription the change fits:
 * freeSeats and yearly apply to all, the others need one.
 *
 * <ul>
 *   <li>freeSeats: pay only for the seats someone holds;
 *   <li>seats: pay for {@code seats} seats;
 *   <li>cancel: drop the subscription;
 *   <li>yearly: a monthly subscription paid for a year, {@code discount} cheaper (0.17 if not given), from its
 *       renewal;
 *   <li>price: a new price of a seat per billing period: another plan, or another vendor.
 * </ul>
 * {@code from} ("2027-01") is the first month the change counts; the first month of the scenario if not given.
 */
enum ChangeKind { freeSeats, seats, cancel, yearly, price }

record ScenarioChange(
        @NotNull ChangeKind kind,
        UUID subscriptionId,
        @Min(0) Integer seats,
        @DecimalMin("0") BigDecimal pricePerSeat,
        @DecimalMin("0") @DecimalMax("0.9") BigDecimal discount,
        @Pattern(regexp = "\\d{4}-\\d{2}") String from) {
}

record ScenarioRequest(@Min(1) @Max(36) Integer horizon, @NotNull @Size(max = 200) List<@Valid ScenarioChange> changes) {
}

/**
 * A month of the scenario: what the registry costs a month as it is and after the changes (a yearly price counts as a
 * twelfth), and the published forecast with the difference taken off, if there is one.
 */
record ScenarioMonth(String month, BigDecimal registry, BigDecimal registryAfter, BigDecimal forecast,
        BigDecimal forecastAfter) {
}

/** What one change saves a month once it counts. */
record ChangeEffect(UUID subscriptionId, String subscription, ChangeKind kind, String description,
        BigDecimal monthlySavings) {
}

/**
 * @param monthlySavings the saving of the last month, when every change counts
 * @param totalSavings over all the months of the scenario
 */
record ScenarioResult(List<ScenarioMonth> months, List<ChangeEffect> effects, BigDecimal monthlySavings,
        BigDecimal totalSavings, boolean onForecast) {
}
