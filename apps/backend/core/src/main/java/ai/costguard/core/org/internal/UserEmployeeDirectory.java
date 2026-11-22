package ai.costguard.core.org.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.Names;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class UserEmployeeDirectory implements EmployeeDirectory {

    private final OrgRepository org;

    UserEmployeeDirectory(OrgRepository org) {
        this.org = org;
    }

    @Override
    public Optional<UUID> employeeOfSubject(String subject) {
        return org.employeeOfSubject(subject);
    }

    @Override
    public Optional<UUID> employeeByEmail(String email) {
        return matchByEmail(email).employee();
    }

    @Override
    public EmployeeMatch matchByEmail(String email) {
        String address = Names.email(email);
        if (address == null) {
            return new EmployeeMatch(EmployeeMatch.Outcome.INVALID, null, 0);
        }
        List<UUID> found = org.employeesByEmail(address);
        return switch (found.size()) {
            case 0 -> new EmployeeMatch(EmployeeMatch.Outcome.NOT_FOUND, null, 0);
            case 1 -> new EmployeeMatch(EmployeeMatch.Outcome.MATCHED, found.get(0), 1);
            default -> new EmployeeMatch(EmployeeMatch.Outcome.AMBIGUOUS, null, found.size());
        };
    }

    @Override
    public Optional<UUID> employeeByName(String name) {
        String wanted = Names.clean(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> found = org.employeeNames().stream().filter(e -> Names.clean(e.name()).equals(wanted))
                .map(OrgRepository.Named::id).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    @Override
    public EmployeeMatch matchByName(String name) {
        String wanted = Names.clean(name);
        if (wanted.isEmpty()) {
            return new EmployeeMatch(EmployeeMatch.Outcome.INVALID, null, 0);
        }
        List<UUID> found = org.workingOrDismissedNames().stream().filter(e -> Names.clean(e.name()).equals(wanted))
                .map(OrgRepository.Named::id).toList();
        return switch (found.size()) {
            case 0 -> new EmployeeMatch(EmployeeMatch.Outcome.NOT_FOUND, null, 0);
            case 1 -> new EmployeeMatch(EmployeeMatch.Outcome.MATCHED, found.get(0), 1);
            default -> new EmployeeMatch(EmployeeMatch.Outcome.AMBIGUOUS, null, found.size());
        };
    }

    @Override
    public Map<UUID, LocalDate> dismissed() {
        return org.dismissed();
    }

    @Override
    public Optional<EmployeeCard> card(UUID employeeId) {
        return org.card(employeeId);
    }

    @Override
    public Optional<CompanyCard> company() {
        return org.companyCard();
    }
}
