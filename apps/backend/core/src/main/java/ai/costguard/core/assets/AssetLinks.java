package ai.costguard.core.assets;

import java.math.BigDecimal;
import java.util.UUID;

/** How imports and connectors (1C, Jamf, Intune, Excel) reach equipment. Joins the caller's transaction. */
public interface AssetLinks {

    /**
     * Registers or refreshes the device. When the report names a holder known to the org structure, the device
     * is issued to them with a handover act.
     *
     * @return the asset, or null when the report names neither a serial nor an inventory number
     */
    UUID reported(UUID tenantId, Reported report);

    /**
     * One device as a source saw it. Any value but one of the numbers may be null; {@code type} is LAPTOP, PHONE,
     * MONITOR or OTHER, and an unknown type is OTHER. {@code source} is the connector name, for the act.
     */
    record Reported(String serialNumber, String inventoryNumber, String model, String type, BigDecimal cost,
            UUID holderId, String source) {
    }
}
