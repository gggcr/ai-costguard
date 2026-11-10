package ai.costguard.core.assets.internal;

import ai.costguard.core.findings.FindingsIntake;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A return settles the findings about the equipment (board frame 15): equipment does not show in invoices, so the
 * return, the same change that goes out to {@code assets.assignments.v1}, is the proof. It runs in the transaction
 * of the return: the finding and the assignment change together or not at all (ADR 0020).
 */
@Component
class ReturnedEquipment {

    /** The kinds {@code asset.duplicate} counts; the same as its names in {@link AssetAuditRules}. */
    private static final Set<AssetType> COUNTED = Set.of(AssetType.LAPTOP, AssetType.PHONE);

    private final FindingsIntake findings;
    private final AssetRepository assets;

    ReturnedEquipment(FindingsIntake findings, AssetRepository assets) {
        this.findings = findings;
        this.assets = assets;
    }

    /** Called after {@code closed} was closed in the repository. */
    void returned(UUID tenantId, Asset asset, Assignment closed) {
        String what = "Техника возвращена: " + asset.model() + " (инв. № " + asset.inventoryNumber() + ")";
        findings.settled(tenantId, AssetAuditRules.NOT_RETURNED + ":" + closed.id(), what);
        findings.settled(tenantId, AssetAuditRules.OVERDUE + ":" + closed.id(), what);
        if (COUNTED.contains(asset.type())) {
            long left = assets.heldBy(closed.employeeId()).stream().filter(a -> a.type() == asset.type()).count();
            if (left < 2) {
                findings.settled(tenantId, AssetAuditRules.DUPLICATE + ":" + closed.employeeId() + ":"
                        + asset.type().name(), what);
            }
        }
    }
}
