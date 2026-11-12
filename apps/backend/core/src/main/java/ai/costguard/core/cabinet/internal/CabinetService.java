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

    /**
     * The person of the token: the account linked to the token's subject, and the employee that account is linked
     * to. Without an account there is no employee card, whatever e-mail the token carries; the token's name and
     * e-mail are then only shown back.
     */
    @Transactional(readOnly = true)
    Me me(UUID tenantId, String subject, String tokenEmail, String tokenName, List<String> roles) {
        session.bind(tenantId);
        Optional<UserDirectory.UserCard> user = users.userBySubject(subject);
        Optional<UUID> employeeId = user.map(UserDirectory.UserCard::employeeId);
        Profile profile = employeeId.flatMap(employees::card)
                .filter(card -> card.dismissedAt() == null)
                .map(c -> new Profile(c.id(), c.name(), c.position(), c.department(), c.email()))
                .orElse(null);
        String name = user.map(UserDirectory.UserCard::name)
                .orElse(tokenName != null ? tokenName : tokenEmail);
        String email = user.map(UserDirectory.UserCard::email).orElse(tokenEmail);
        return new Me(user.map(UserDirectory.UserCard::id).orElse(null), name, email, roles, profile);
    }
}
