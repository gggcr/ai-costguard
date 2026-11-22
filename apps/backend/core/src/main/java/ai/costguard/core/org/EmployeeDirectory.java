package ai.costguard.core.org;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Links a login, an e-mail or a name to a person. Call inside a transaction already bound to the tenant. */
public interface EmployeeDirectory {

    /** The employee linked to the account of this token subject (sub); empty without an account or a link. */
    Optional<UUID> employeeOfSubject(String subject);

    /**
     * The employee with this e-mail, ignoring case, when it names exactly one employee of the company; empty when it
     * names none or several. For matching data (an import), never to decide who the signed-in person is.
     */
    Optional<UUID> employeeByEmail(String email);

    /**
     * How a reported address names an employee of the company (finding R1 of 2026-11-12): the address is normalised
     * as the application does (trimmed, lower case, checked), then compared ignoring case with the stored addresses of
     * the employees who are not erased, working or dismissed. Exactly one is a match; several are ambiguous, and none
     * of them is chosen. For matching data, never to decide who the signed-in person is.
     */
    EmployeeMatch matchByEmail(String email);

    /**
     * How a reported full name names an employee of the company, for a payment that carries no address: the name is
     * cleaned as {@link #employeeByName} does and compared with the employees who are not erased. Exactly one is a
     * match; namesakes are ambiguous, and none of them is chosen.
     */
    EmployeeMatch matchByName(String name);

    /**
     * {@code employeeId} only for {@link Outcome#MATCHED}; {@code candidates}: how many employees have the address
     * (or the name).
     */
    record EmployeeMatch(Outcome outcome, UUID employeeId, int candidates) {

        public enum Outcome { MATCHED, NOT_FOUND, AMBIGUOUS, INVALID }

        public Optional<UUID> employee() {
            return outcome == Outcome.MATCHED ? Optional.of(employeeId) : Optional.empty();
        }
    }

    /** The only employee with this cleaned full name; empty when there is none or several namesakes. */
    Optional<UUID> employeeByName(String name);

    /** Dismissed employees and the day each left. */
    Map<UUID, LocalDate> dismissed();

    /** Who the employee is, as documents name them; empty when the tenant has no such employee. */
    Optional<EmployeeCard> card(UUID employeeId);

    /** The company as documents name it; empty until its settings are saved. */
    Optional<CompanyCard> company();

    record EmployeeCard(UUID id, String name, String position, String department, String email,
            LocalDate dismissedAt) {
    }

    /**
     * {@code currency} is what every amount of the company without a currency of its own is in (ADR 0072);
     * {@code country} (ISO 3166-1 alpha-2, null for a company that never said) is how its people write numbers and
     * dates; {@code zone} is what "today" and "every morning" mean for it (ADR 0073); {@code language} is what
     * the core writes its texts in (ADR 0074).
     */
    record CompanyCard(String name, String currency, String country, java.time.ZoneId zone,
            ai.costguard.core.shared.names.Language language) {
    }
}
