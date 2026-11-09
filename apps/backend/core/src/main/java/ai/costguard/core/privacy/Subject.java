package ai.costguard.core.privacy;

import java.util.Set;
import java.util.UUID;

/**
 * The person being erased, as every store may know them. {@code emails} are lowercase: the employee's own and those
 * of their CostGuard users. {@code accounts} are their Keycloak user ids, the {@code sub} of their tokens.
 * {@code nameKey} is the cleaned full name the ingest pipeline keys people by when it has no e-mail.
 * {@code pseudonym} replaces them where a record must keep saying that somebody acted, as in "decided by".
 */
public record Subject(UUID tenantId, UUID employeeId, Set<String> emails, Set<String> accounts, String nameKey,
        String pseudonym) {

    public Subject {
        emails = Set.copyOf(emails);
        accounts = Set.copyOf(accounts);
    }

    public boolean isTheirs(String email) {
        return email != null && emails.contains(email.strip().toLowerCase(java.util.Locale.ROOT));
    }
}
