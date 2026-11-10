package ai.costguard.core.org.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keycloak in memory, for tests: what the core asked for, and accounts set up by the test. */
public class FakeIdentityProvider implements IdentityProvider {

    public record Stored(String id, String email, String firstName, String lastName, UUID tenantId, String role,
            boolean emailVerified) {
    }

    public final Map<String, Stored> accounts = new ConcurrentHashMap<>();
    public final List<String> setupEmails = new CopyOnWriteArrayList<>();
    public volatile boolean configured = true;
    public volatile boolean failEmails;

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public Optional<String> create(NewAccount account) {
        if (byEmail(account.email()).isPresent()) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        accounts.put(id, new Stored(id, account.email(), account.firstName(), account.lastName(), account.tenantId(),
                account.role(), false));
        return Optional.of(id);
    }

    public Optional<Stored> stored(String email) {
        return accounts.values().stream().filter(a -> a.email().equalsIgnoreCase(email)).findFirst();
    }

    private static Account account(Stored s) {
        return new Account(s.id(), s.email(), s.tenantId(), s.emailVerified());
    }

    @Override
    public Optional<Account> byEmail(String email) {
        return stored(email).map(FakeIdentityProvider::account);
    }

    @Override
    public Optional<Account> byId(String id) {
        return Optional.ofNullable(accounts.get(id)).map(FakeIdentityProvider::account);
    }

    @Override
    public void sendSetupEmail(String id) {
        if (failEmails) {
            throw new Unavailable("SMTP down");
        }
        if (!accounts.containsKey(id)) {
            throw new Unavailable("no such account");
        }
        setupEmails.add(id);
    }

    @Override
    public void delete(String id) {
        accounts.remove(id);
    }

    /** The person followed the link: the address is confirmed and a password set. */
    public void confirm(String email) {
        Stored s = stored(email).orElseThrow();
        accounts.put(s.id(), new Stored(s.id(), s.email(), s.firstName(), s.lastName(), s.tenantId(), s.role(), true));
    }

    /** An account the core did not create, e.g. a user of another company. */
    public String existing(String email, UUID tenantId) {
        String id = UUID.randomUUID().toString();
        accounts.put(id, new Stored(id, email, "Someone", "Else", tenantId, "owner", true));
        return id;
    }

    public long emailsTo(String email) {
        return stored(email).map(s -> setupEmails.stream().filter(s.id()::equals).count()).orElse(0L);
    }
}
