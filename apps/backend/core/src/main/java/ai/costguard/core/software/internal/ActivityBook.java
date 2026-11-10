package ai.costguard.core.software.internal;

import ai.costguard.core.shared.names.Names;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Activity of people in products (ACTIVITY_EVENT, ADR 0042). An event is kept once; it moves the last activity of
 * the person's license in that product forward, and a last activity reported for a license (a connector, a file,
 * a person) is kept as an event of that day. So the last activity of a license is always the newest event.
 */
@Component
class ActivityBook {

    private final ProductRepository products;
    private final SoftwareRepository software;

    ActivityBook(ProductRepository products, SoftwareRepository software) {
        this.products = products;
        this.software = software;
    }

    /** The product of the subscription, linked when it had none yet. */
    UUID productOf(UUID tenantId, Subscription s) {
        if (s.productId() != null) {
            return s.productId();
        }
        UUID product = products.productOf(tenantId, s.vendorId(), s.product());
        products.linkSubscription(s.id(), product);
        return product;
    }

    /** A last activity of a license: an event of that day. */
    void licenseActive(UUID tenantId, Subscription s, UUID employeeId, LocalDate day, String source) {
        products.recordActivity(tenantId, employeeId, productOf(tenantId, s), s.id(),
                day.atStartOfDay(ZoneOffset.UTC).toInstant(), "sign_in", source);
    }

    /** An event a source reported; returns false when it was recorded before. */
    boolean seen(UUID tenantId, UUID vendorId, String productName, UUID employeeId, Instant at, String kind,
            String source) {
        Optional<Subscription> subscription = software.subscriptions().stream()
                .filter(s -> s.vendorId().equals(vendorId) && Names.clean(s.product()).equals(Names.clean(productName)))
                .findFirst();
        UUID product = subscription.map(s -> productOf(tenantId, s))
                .orElseGet(() -> products.productOf(tenantId, vendorId, productName));
        boolean recorded = products.recordActivity(tenantId, employeeId, product,
                subscription.map(Subscription::id).orElse(null), at, kind, source);
        subscription.flatMap(s -> software.licenseOf(s.id(), employeeId))
                .ifPresent(l -> software.setActivity(l.id(), at.atOffset(ZoneOffset.UTC).toLocalDate()));
        return recorded;
    }
}
