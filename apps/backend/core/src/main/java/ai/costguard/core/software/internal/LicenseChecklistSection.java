package ai.costguard.core.software.internal;

import ai.costguard.core.org.DismissalChecklistSection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The license half of the dismissal checklist; cost is the monthly price of the seat. */
@Component
class LicenseChecklistSection implements DismissalChecklistSection {

    private final SoftwareRepository software;

    LicenseChecklistSection(SoftwareRepository software) {
        this.software = software;
    }

    @Override
    public String name() {
        return "licenses";
    }

    @Override
    public List<Item> itemsOf(UUID employeeId) {
        return software.heldBy(employeeId).stream()
                .map(h -> new Item(h.vendor() + ": " + h.product(), h.pricePerSeat()))
                .toList();
    }
}
