package ai.costguard.core.software;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The subscriptions of the registry, for scenarios. Call inside a transaction already bound to the tenant. */
public interface SubscriptionCatalog {

    List<Held> subscriptions();

    /**
     * @param pricePerSeat per billing period, as the audit reads it
     * @param assigned the seats someone holds
     */
    record Held(UUID id, String vendor, String product, UUID departmentId, int seats, int assigned,
            BigDecimal pricePerSeat, boolean yearly, LocalDate renewalDate) {
    }
}
