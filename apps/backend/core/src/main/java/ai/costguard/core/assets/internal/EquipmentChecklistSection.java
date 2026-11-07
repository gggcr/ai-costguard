package ai.costguard.core.assets.internal;

import ai.costguard.core.org.DismissalChecklistSection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The equipment half of the dismissal checklist. */
@Component
class EquipmentChecklistSection implements DismissalChecklistSection {

    private final AssetRepository assets;

    EquipmentChecklistSection(AssetRepository assets) {
        this.assets = assets;
    }

    @Override
    public String name() {
        return "equipment";
    }

    @Override
    public List<Item> itemsOf(UUID employeeId) {
        return assets.heldBy(employeeId).stream()
                .map(asset -> new Item(asset.model() + " (" + asset.inventoryNumber() + ")", asset.cost()))
                .toList();
    }
}
