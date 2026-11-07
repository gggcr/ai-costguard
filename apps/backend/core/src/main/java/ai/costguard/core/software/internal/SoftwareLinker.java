package ai.costguard.core.software.internal;

import ai.costguard.core.shared.names.Names;
import ai.costguard.core.software.SoftwareLinks;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class SoftwareLinker implements SoftwareLinks {

    private final SoftwareRepository software;

    SoftwareLinker(SoftwareRepository software) {
        this.software = software;
    }

    @Override
    public UUID vendor(UUID tenantId, String name, String inn) {
        String code = Names.inn(inn);
        String cleaned = Names.clean(name);
        var vendors = software.vendors();
        if (code != null) {
            var byInn = vendors.stream().filter(v -> code.equals(Names.inn(v.inn()))).findFirst();
            if (byInn.isPresent()) {
                return byInn.get().id();
            }
        }
        var byName = vendors.stream().filter(v -> !cleaned.isEmpty() && Names.clean(v.name()).equals(cleaned))
                .findFirst();
        if (byName.isPresent()) {
            Vendor v = byName.get();
            if (code != null && Names.inn(v.inn()) == null) {
                software.updateVendor(v.id(), new VendorDetails(v.name(), code, v.category()));
            }
            return v.id();
        }
        UUID id = UUID.randomUUID();
        software.insertVendor(tenantId, id, new VendorDetails(name.strip(), code == null ? "" : code, ""));
        return id;
    }

    @Override
    public Optional<UUID> subscription(UUID vendorId, String product) {
        String wanted = Names.clean(product);
        return software.subscriptions().stream()
                .filter(s -> s.vendorId().equals(vendorId) && Names.clean(s.product()).equals(wanted))
                .map(Subscription::id).findFirst();
    }

    @Override
    public Optional<UUID> licenseSeen(UUID tenantId, UUID subscriptionId, UUID employeeId, LocalDate lastActive) {
        var subscription = software.subscriptionForUpdate(subscriptionId);
        if (subscription.isEmpty()) {
            return Optional.empty();
        }
        var license = software.licenseOf(subscriptionId, employeeId).map(License::id);
        if (license.isEmpty()) {
            if (subscription.get().assigned() >= subscription.get().seats()) {
                return Optional.empty();
            }
            UUID id = UUID.randomUUID();
            software.insertLicense(tenantId, id, subscriptionId, employeeId);
            license = Optional.of(id);
        }
        if (lastActive != null) {
            software.setActivity(license.get(), lastActive);
        }
        return license;
    }
}
