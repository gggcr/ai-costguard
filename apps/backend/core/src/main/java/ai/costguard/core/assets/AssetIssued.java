package ai.costguard.core.assets;

import java.time.LocalDate;
import java.util.UUID;

/** A device was issued to an employee with its handover act (ADR 0052: the cabinet tells the employee). */
public record AssetIssued(UUID tenantId, UUID assetId, UUID assignmentId, UUID employeeId, String model,
        String inventoryNumber, String actNumber, LocalDate issuedOn) {
}
