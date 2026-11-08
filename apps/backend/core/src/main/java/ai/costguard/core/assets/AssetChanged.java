package ai.costguard.core.assets;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The full current state of an asset after any change, for the audit engine. */
public record AssetChanged(UUID tenantId, UUID assetId, String inventoryNumber, String model, String type,
        BigDecimal cost, String status, UUID employeeId, LocalDate issuedAt, LocalDate returnDue, Instant changedAt) {
}
