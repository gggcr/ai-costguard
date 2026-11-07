package ai.costguard.core.software;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** How imports and connectors reach vendors and licenses. Call inside a transaction already bound to the tenant. */
public interface SoftwareLinks {

    /**
     * The vendor with this INN, else the one whose cleaned name matches; a new vendor when there is none. An INN
     * learned this way is written to a vendor that had none.
     */
    UUID vendor(UUID tenantId, String name, String inn);

    /** The subscription of this vendor to this product, matched by the cleaned product name. */
    Optional<UUID> subscription(UUID vendorId, String product);

    /**
     * The license of this person; a new one when the subscription has a free seat. The activity only moves
     * forward. Empty when there is no license and no free seat: the core will not invent paid seats.
     */
    Optional<UUID> licenseSeen(UUID tenantId, UUID subscriptionId, UUID employeeId, LocalDate lastActive);
}
