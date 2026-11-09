package ai.costguard.core.privacy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Who a person is, from the org module. Called inside a transaction already bound to the tenant. */
public interface ErasureSubjects {

    /**
     * {@code emails} lowercase: the employee's and their CostGuard users'. {@code lastOwner} is true when one of
     * those users is the only owner of the company, who cannot be erased.
     */
    record Person(UUID employeeId, Set<String> emails, String nameKey, LocalDate dismissedAt, boolean erased,
            boolean lastOwner) {
    }

    Optional<Person> person(UUID employeeId);

    /** Employees dismissed before the day and not erased yet. */
    List<UUID> dismissedBefore(LocalDate day);

    /** The company name, which an owner types to confirm erasing the company; empty until it is saved. */
    Optional<String> companyName();
}
