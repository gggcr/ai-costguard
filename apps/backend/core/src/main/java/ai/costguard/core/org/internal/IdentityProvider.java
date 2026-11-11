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

    /**
     * Log in with the company's Microsoft Entra ID (ADR 0058): an OpenID Connect provider of the realm under
     * {@code alias}, hidden on the login page (the work e-mail leads to it). It only logs in people who already have
     * an account (invited); the first time the account is linked after a confirmation. {@code clientSecret} null
     * keeps the one Keycloak has.
     */
    record MicrosoftLogin(String alias, UUID tenantId, String directoryId, String clientId, String clientSecret,
            String displayName) {
    }

    void saveMicrosoftLogin(MicrosoftLogin login);

    /**
     * An account that can log in right away (ADR 0059): the address is the company's own (its mailbox was just made
     * or an owner typed it), so it is verified; the password is the one the person is given.
     */
    Optional<String> createWithPassword(NewAccount account, String password);

    /** How the account logs in: whether it has a code of an authenticator app, and whether the code comes first. */
    record SignIn(boolean otp, boolean otpFirst) {
    }

    SignIn signIn(String id);

    /** The code of the authenticator app is asked first on the login page; the password stays another way. */
    void preferOtp(String id);

    /** Removing a provider that is gone already is fine. */
    void removeLogin(String alias);

    /** {@code tenantId} null: the account belongs to no company (an administrator, a broken attribute). */
    record Account(String id, String email, UUID tenantId, boolean emailVerified) {
    }
}
