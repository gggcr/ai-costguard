package ai.costguard.core.software;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * An invoice a source reported, stored or corrected by its entity key. A new one is tied to its subscription
     * and its payments, and the findings in progress on that subscription look for their saving in it (ADR 0033).
     * Returns the invoice id.
     */
    UUID invoiceSeen(UUID tenantId, ReportedInvoice invoice);

    /** A payment a source reported, stored or corrected by its entity key, and tied to the invoice it settles. */
    UUID paymentSeen(UUID tenantId, ReportedPayment payment);

    /**
     * A sign-in or use of a product a source reported (ACTIVITY_EVENT, ADR 0042): kept once, and the last activity of
     * the person's license in that product moves forward. A product nobody subscribes to yet is still kept.
     */
    void activitySeen(UUID tenantId, UUID vendorId, String product, UUID employeeId, Instant at, String kind,
            String source);

    /**
     * @param issuedOn the date of the invoice; with {@code dated} false, the day it arrived
     * @param source where it came from: "mail", "1c", a file name
     */
    record ReportedInvoice(String entityKey, UUID vendorId, String number, LocalDate issuedOn, boolean dated,
            String currency, BigDecimal total, String source, List<Line> lines, Instant at) {

        public record Line(String description, BigDecimal amount) {
        }
    }

    /**
     * @param kind TRANSFER, CARD, SBP... as the bank said; empty when unknown
     * @param role payment, service or commission (ADR 0015)
     */
    record ReportedPayment(String entityKey, UUID vendorId, BigDecimal amount, String currency, LocalDate paidOn,
            boolean dated, String purpose, String kind, String card, String mcc, String role, String intermediary,
            String source, Instant at) {
    }
}
