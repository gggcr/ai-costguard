package ai.costguard.core.org.internal;

import ai.costguard.core.org.UserDirectory;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class RepositoryUserDirectory implements UserDirectory {

    private final OrgRepository org;

    RepositoryUserDirectory(OrgRepository org) {
        this.org = org;
    }

    private static UserCard card(UserAccount u) {
        return new UserCard(u.id(), u.name(), u.email(), u.role().name(), u.employeeId());
    }

    @Override
    public List<UserCard> users() {
        return org.users().stream().map(RepositoryUserDirectory::card).toList();
    }

    @Override
    public Optional<UserCard> userByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return org.users().stream().filter(u -> u.email().equalsIgnoreCase(email.trim())).findFirst()
                .map(RepositoryUserDirectory::card);
    }
}
