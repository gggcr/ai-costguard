package ai.costguard.core.software.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        BigDecimal pricePerSeat, BillingPeriod billingPeriod, LocalDate renewalDate, int assigned, String plan,
        UUID productId) {
}

record SubscriptionDetails(
        @NotNull UUID vendorId,
        @NotBlank String product,
        UUID contractId,
        @NotNull UUID departmentId,
        @Min(0) int seats,
        @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal pricePerSeat,
        @NotNull BillingPeriod billingPeriod,
        @NotNull LocalDate renewalDate,
        @Size(max = 200) String plan) {
}

/**
 * A plan a product is sold on. Prices are of the whole period: per seat when {@code perSeat}, else for the whole
 * plan. {@code maxUsers} is null when the plan allows any number of users.
 */
record Plan(UUID id, UUID vendorId, String product, String name, boolean perSeat, Integer maxUsers,
        BigDecimal priceMonth, BigDecimal priceYear) {
}

record PlanDetails(
        @NotNull UUID vendorId,
        @NotBlank @Size(max = 200) String product,
        @NotBlank @Size(max = 200) String name,
        boolean perSeat,
        @Min(1) Integer maxUsers,
        @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal priceMonth,
        @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal priceYear) {
}

record License(UUID id, UUID subscriptionId, UUID employeeId, LocalDate lastActive) {
}

record NewLicense(@NotNull UUID employeeId) {
}

record Activity(@NotNull LocalDate lastActive) {
}

/**
 * What a vendor sells (PRODUCT, ADR 0042). {@code category} is what it is for, set by a person; null when nobody did,
 * and the overlap rule then reads the name.
 */
record Product(UUID id, UUID vendorId, String name, String category, int subscriptions, java.time.Instant lastActivity) {
}

record ProductCategory(@Size(max = 40) String category) {
}

/** A sign-in or use of a product (ACTIVITY_EVENT, ADR 0042). */
record ActivityEvent(UUID employeeId, java.time.Instant occurredAt, String kind, String source) {
}
