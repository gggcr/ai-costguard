package ai.costguard.core.software.internal;

import ai.costguard.core.software.SoftwareInventory;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class RepositorySoftwareInventory implements SoftwareInventory {

    private final SoftwareRepository software;

    RepositorySoftwareInventory(SoftwareRepository software) {
        this.software = software;
    }

    @Override
    public Map<UUID, String> vendorNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        software.vendors().forEach(v -> names.put(v.id(), v.name()));
        return names;
    }

    @Override
    public Set<UUID> registeredVendors() {
        return new HashSet<>(software.registeredVendors());
    }

    @Override
    public List<Billing> billing() {
        return software.subscriptions().stream()
                .map(s -> new Billing(s.id(), s.vendorId(), s.billingPeriod() == BillingPeriod.year,
                        s.pricePerSeat().multiply(BigDecimal.valueOf(s.seats()))))
                .toList();
    }
}
