package ai.costguard.core.org.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Accounts people log in with (ADR 0036): Keycloak in every deployment ({@link KeycloakIdentityProvider}), an
 * in-memory fake in tests. The account carries the company in its {@code tenant_id} attribute and the role as a
 * realm role, which is what the core reads from the token.
 */
interface IdentityProvider {

    /** The provider answered unexpectedly or not at all; nothing was decided, the caller may try again. */
    class Unavailable extends RuntimeException {
        Unavailable(String what) {
            super(what);
        }
    }

    /** False while the deployment has no credentials for it: registration and invitations are then off. */
    boolean configured();

    /**
     * Creates an enabled account that must confirm its e-mail and set a password before it can log in, with the
     * company attribute and the role. Empty when an account with this e-mail exists already.
     */
    Optional<String> create(NewAccount account);

    Optional<Account> byEmail(String email);

    Optional<Account> byId(String id);

    /** E-mails a link to confirm the address and set a password, valid for {@code costguard.registration.link-lifespan}. */
    void sendSetupEmail(String id);

    /** Deleting an account that is gone already is fine. */
    void delete(String id);

    record NewAccount(String email, String firstName, String lastName, UUID tenantId, String role) {
    }

    /** {@code tenantId} null: the account belongs to no company (an administrator, a broken attribute). */
    record Account(String id, String email, UUID tenantId, boolean emailVerified) {
    }
}
