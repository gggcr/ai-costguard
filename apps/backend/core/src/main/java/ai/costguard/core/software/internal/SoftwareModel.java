package ai.costguard.core.software.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

enum BillingPeriod { month, year }

record Vendor(UUID id, String name, String inn, String category) {
}

record VendorDetails(@NotBlank String name, @NotNull String inn, @NotNull String category) {
}

record Contract(UUID id, UUID vendorId, String number, LocalDate startDate, LocalDate endDate, int noticeDays,
        boolean autoRenew, BigDecimal amount) {
}

record ContractDetails(
        @NotNull UUID vendorId,
        @NotBlank String number,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Min(0) int noticeDays,
        boolean autoRenew,
        @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount) {
}

/** {@code pricePerSeat} is per month, whatever the billing period. */
record Subscription(UUID id, UUID vendorId, String product, UUID contractId, UUID departmentId, int seats,
        BigDecimal pricePerSeat, BillingPeriod billingPeriod, LocalDate renewalDate, int assigned) {
}

record SubscriptionDetails(
        @NotNull UUID vendorId,
        @NotBlank String product,
        UUID contractId,
        @NotNull UUID departmentId,
        @Min(0) int seats,
        @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal pricePerSeat,
        @NotNull BillingPeriod billingPeriod,
        @NotNull LocalDate renewalDate) {
}

record License(UUID id, UUID subscriptionId, UUID employeeId, LocalDate lastActive) {
}

record NewLicense(@NotNull UUID employeeId) {
}

record Activity(@NotNull LocalDate lastActive) {
}
