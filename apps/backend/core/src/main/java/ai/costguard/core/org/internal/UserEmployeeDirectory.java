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
    public Optional<UUID> employeeOfUser(String email) {
        return org.employeeOfUser(email);
    }

    @Override
    public Optional<UUID> employeeByEmail(String email) {
        String address = Names.email(email);
        return address == null ? Optional.empty() : org.employeeByEmail(address);
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
    public Map<UUID, LocalDate> dismissed() {
        return org.dismissed();
    }
}
