package ai.costguard.core.software.internal;

import ai.costguard.core.software.SubscriptionCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class RepositorySubscriptionCatalog implements SubscriptionCatalog {

    private final SoftwareRepository software;

    RepositorySubscriptionCatalog(SoftwareRepository software) {
        this.software = software;
    }

    @Override
    public List<Held> subscriptions() {
        Map<UUID, String> vendors = new HashMap<>();
        software.vendors().forEach(v -> vendors.put(v.id(), v.name()));
        return software.subscriptions().stream()
                .map(s -> new Held(s.id(), vendors.getOrDefault(s.vendorId(), ""), s.product(), s.departmentId(),
                        s.seats(), s.assigned(), s.pricePerSeat(), s.billingPeriod() == BillingPeriod.year,
                        s.renewalDate()))
                .toList();
    }
}
