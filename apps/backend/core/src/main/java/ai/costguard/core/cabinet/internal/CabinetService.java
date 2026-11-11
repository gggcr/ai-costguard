package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.UserDirectory;
import ai.costguard.platform.tenant.TenantSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CabinetService {

    private final UserDirectory users;
    private final EmployeeDirectory employees;
    private final TenantSession session;

    CabinetService(UserDirectory users, EmployeeDirectory employees, TenantSession session) {
        this.users = users;
        this.employees = employees;
        this.session = session;
    }

    /** The person of the token: the user record if there is one, and the employee it is linked to or shares an e-mail with. */
    @Transactional(readOnly = true)
    Me me(UUID tenantId, String email, String tokenName, List<String> roles) {
        session.bind(tenantId);
        var user = users.userByEmail(email);
        Optional<UUID> employeeId = user.map(UserDirectory.UserCard::employeeId)
                .or(() -> employees.employeeOfUser(email))
                .or(() -> employees.employeeByEmail(email));
        Profile profile = employeeId.flatMap(employees::card)
                .filter(card -> card.dismissedAt() == null)
                .map(c -> new Profile(c.id(), c.name(), c.position(), c.department(), c.email()))
                .orElse(null);
        String name = user.map(UserDirectory.UserCard::name)
                .orElse(profile != null ? profile.name() : tokenName != null ? tokenName : email);
        return new Me(user.map(UserDirectory.UserCard::id).orElse(null), name, email, roles, profile);
    }
}
