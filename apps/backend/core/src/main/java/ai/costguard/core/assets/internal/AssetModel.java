package ai.costguard.core.assets.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

enum AssetStatus { IN_STOCK, ASSIGNED, RETIRED }

record NewAsset(
        @NotBlank String inventoryNumber,
        @NotBlank String model,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal cost) {
}

record Assignment(
        UUID id,
        UUID assetId,
        UUID employeeId,
        LocalDate issuedAt,
        LocalDate returnDue,
        LocalDate returnedAt) {
}

record Asset(
        UUID id,
        String inventoryNumber,
        String model,
        BigDecimal cost,
        AssetStatus status,
        Assignment currentAssignment) {
}

record NewAssignment(@NotNull UUID employeeId, LocalDate returnDue) {
}

record AssetPage(List<Asset> items, int page, int size) {
}

/** What an employee still holds: the equipment half of the dismissal checklist. */
record EmployeeAssets(UUID employeeId, List<Asset> items, BigDecimal totalCost) {
}
