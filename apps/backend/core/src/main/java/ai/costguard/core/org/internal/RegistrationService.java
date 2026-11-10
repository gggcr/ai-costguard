package ai.costguard.core.org.internal;

import ai.costguard.core.plans.PlanEnrollment;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-registration (ADR 0032). A company and its owner's account are created at once, the company pending; Keycloak
 * e-mails the owner a link to confirm the address and set a password, and only then can the owner log in. The first
 * login activates the company ({@link FirstLogin}); a registration nobody confirms is dropped by {@link
 * RegistrationSweeper}.
 *
 * <p>Nothing goes to the activity log before the confirmation: that log keeps everything, and an abandoned
 * registration must leave nothing behind.
 */
@Service
class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private final IdentityProvider accounts;
    private final RegistrationRepository registrations;
    private final OrgRepository org;
    private final RegistrationAttempts attempts;
    private final PlanEnrollment plans;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final RegistrationProperties properties;

    RegistrationService(IdentityProvider accounts, RegistrationRepository registrations, OrgRepository org,
            RegistrationAttempts attempts, PlanEnrollment plans, TenantSession session,
            TransactionTemplate transactions, Clock clock, ApplicationEventPublisher events,
            RegistrationProperties properties) {
        this.accounts = accounts;
        this.registrations = registrations;
        this.org = org;
        this.attempts = attempts;
        this.plans = plans;
        this.session = session;
        this.transactions = transactions;
        this.clock = clock;
        this.events = events;
        this.properties = properties;
    }

    static String email(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    private static void requireCodes(String country, String currency) {
        if (!COUNTRIES.contains(country)) {
            throw RegistrationProblem.COUNTRY_UNKNOWN.failure();
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw RegistrationProblem.CURRENCY_UNKNOWN.failure();
        }
    }

    /**
     * The answer is the same for a new e-mail, a pending registration (the link is sent again) and an e-mail with an
     * account already (nothing is sent): the reply must not tell who is registered.
     */
    RegistrationAccepted register(RegistrationRequest request, String clientAddress) {
        if (!accounts.configured()) {
            throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
        }
        requireCodes(request.country(), request.currency());
        String email = email(request.email());
        if (!attempts.allow(clientAddress, email)) {
            throw RegistrationProblem.TOO_MANY_REGISTRATIONS.failure();
        }
        try {
            Optional<IdentityProvider.Account> existing = accounts.byEmail(email);
            if (existing.isPresent()) {
                resendIfPending(existing.get());
                return RegistrationAccepted.CHECK_EMAIL;
            }
            UUID tenantId = UUID.randomUUID();
            Optional<String> created = accounts.create(new IdentityProvider.NewAccount(email,
                    request.ownerFirstName().strip(), request.ownerLastName().strip(), tenantId, "owner"));
            if (created.isEmpty()) {
                return RegistrationAccepted.CHECK_EMAIL; // taken between the two calls
            }
            String accountId = created.get();
            try {
                transactions.executeWithoutResult(tx -> {
                    session.bind(tenantId);
                    Instant now = clock.instant();
                    registrations.insertTenant(tenantId, request.companyName().strip(), request.currency(),
                            request.country());
                    org.insertUser(tenantId, UUID.randomUUID(), new NewUser(request.ownerFirstName().strip() + " "
                            + request.ownerLastName().strip(), email, UserRole.owner, null));
                    registrations.insertRegistration(tenantId, accountId, email, now,
                            now.plus(properties.unconfirmedFor()));
                    plans.startTrial(tenantId);
                });
            } catch (RuntimeException e) {
                forgetAccount(accountId);
                throw e;
            }
            sendSetupEmail(accountId);
            return RegistrationAccepted.CHECK_EMAIL;
        } catch (IdentityProvider.Unavailable e) {
            log.warn("Registration failed: {}", e.getMessage());
            throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
        }
    }

    private void resendIfPending(IdentityProvider.Account account) {
        if (account.tenantId() == null || account.emailVerified()) {
            return;
        }
        Optional<RegistrationRepository.Pending> pending = transactions.execute(tx -> {
            session.bind(account.tenantId());
            return registrations.registration();
        });
        if (pending != null && pending.isPresent() && !pending.get().confirmed()
                && pending.get().accountId().equals(account.id())) {
            sendSetupEmail(account.id());
        }
    }

    private void sendSetupEmail(String accountId) {
        try {
            accounts.sendSetupEmail(accountId);
        } catch (IdentityProvider.Unavailable e) {
            log.warn("Setup e-mail failed: {}", e.getMessage());
            throw RegistrationProblem.SETUP_EMAIL_FAILED.failure();
        }
    }

    private void forgetAccount(String accountId) {
        try {
            accounts.delete(accountId);
        } catch (IdentityProvider.Unavailable e) {
            log.warn("An account of a failed registration was left in Keycloak: {}", e.getMessage());
        }
    }

    /**
     * The owner confirmed the e-mail: the company becomes active and the registration is no longer dropped. Inside a
     * transaction bound to the tenant. True when the company was pending.
     */
    boolean activate(UUID tenantId) {
        if (!registrations.activate()) {
            return false;
        }
        registrations.confirmed(tenantId, clock.instant());
        events.publishEvent(ActionTaken.bySystem("registration", tenantId, "company.activated", "tenant", tenantId,
                Map.of()));
        return true;
    }

    // --- The sweeper

    List<UUID> expired() {
        return transactions.execute(tx -> registrations.expired(clock.instant(), 50));
    }

    /**
     * An unconfirmed registration past its term: activated if the owner confirmed the e-mail but has not logged in
     * yet, otherwise deleted with its account, so the e-mail can register again.
     */
    void sweep(UUID tenantId) {
        Optional<RegistrationRepository.Pending> pending = transactions.execute(tx -> {
            if (!registrations.lockTimer(tenantId)) {
                return Optional.<RegistrationRepository.Pending>empty();
            }
            session.bind(tenantId);
            return registrations.registration();
        });
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String accountId = pending.get().accountId();
        Optional<IdentityProvider.Account> account = accounts.byId(accountId);
        if (account.isPresent() && account.get().emailVerified()) {
            transactions.executeWithoutResult(tx -> {
                session.bind(tenantId);
                activate(tenantId);
            });
            return;
        }
        accounts.delete(accountId);
        transactions.executeWithoutResult(tx -> {
            session.bind(tenantId);
            plans.forget(tenantId);
            registrations.deleteTenant(tenantId);
        });
        log.info("Dropped unconfirmed registration of tenant {}", tenantId);
    }
}
