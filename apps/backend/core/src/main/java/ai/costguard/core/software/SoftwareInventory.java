package ai.costguard.core.software;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the registry knows the company pays for: the audit of invoices and payments reads it. Call inside a
 * transaction already bound to the tenant.
 */
public interface SoftwareInventory {

    /** Vendor id -> name. */
    Map<UUID, String> vendorNames();

    /** Vendors with a subscription or a contract. */
    Set<UUID> registeredVendors();

    /** Every subscription with what it costs in its billing period: seats times the price of a seat. */
    List<Billing> billing();

    /** @param yearly the subscription is paid for a year at a time, else for a month */
    record Billing(UUID subscriptionId, UUID vendorId, boolean yearly, BigDecimal amount) {
    }
}
