package ai.costguard.core.org;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A module's part of the dismissal checklist. Called inside a transaction already bound to the tenant. */
public interface DismissalChecklistSection {

    String name();

    List<Item> itemsOf(UUID employeeId);

    /** {@code cost} is shown for information; deductions are decided by HR and accounting. */
    record Item(String title, BigDecimal cost) {
    }
}
