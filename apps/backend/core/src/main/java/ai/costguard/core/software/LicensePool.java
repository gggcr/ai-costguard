package ai.costguard.core.software;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The company's licenses as a pool (ADR 0055): what a person holds, what is free or idle, and giving or taking back
 * a license. Call inside a transaction already bound to the tenant.
 */
public interface LicensePool {

    /** The licenses of this employee. */
    List<Held> heldBy(UUID employeeId);

    /** Every subscription with its seats: free ones and licenses idle at least {@code idleDays}. No holder names. */
    List<Offer> offers(int idleDays);

    Optional<Offer> offer(UUID subscriptionId, int idleDays);

    /** Gives the license when the subscription has a free seat; empty when it has none. Holding it already is fine. */
    Optional<UUID> grant(UUID subscriptionId, UUID employeeId);

    /** The license idle longest in this subscription (no activity for {@code idleDays}, or never seen), if any. */
    Optional<Idle> idlest(UUID subscriptionId, int idleDays, UUID except);

    /** Every idle license, for taking them back into the pool. */
    List<Idle> idle(int idleDays);

    /** Takes a license back into the pool; false when it is gone already. */
    boolean reclaim(UUID licenseId);

    /** The subscription whose product or vendor this text names (cleaned, either way round), if exactly one. */
    Optional<Offer> match(String text, int idleDays);

    /** What one seat of each subscription costs a month (a yearly price divided by 12): what a seat not bought saves. */
    java.util.Map<UUID, java.math.BigDecimal> monthlySeatPrices();

    /** The person with this e-mail used the subscription's program that day: their license is not idle. */
    boolean used(UUID subscriptionId, String email, LocalDate day);

    record Held(UUID licenseId, UUID subscriptionId, String vendor, String product, LocalDate lastActive) {
    }

    /** {@code free}: seats nobody holds; {@code idle}: licenses nobody used for the idle days. */
    record Offer(UUID subscriptionId, String vendor, String product, int seats, int assigned, int free, int idle) {
    }

    record Idle(UUID licenseId, UUID subscriptionId, UUID employeeId, String product, LocalDate lastActive) {
    }
}
