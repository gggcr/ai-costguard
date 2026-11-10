package ai.costguard.core.org.internal;

import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An owner invites a person with a role (ADR 0032): the person gets a Keycloak account of the company with that
 * role, a CostGuard user, and an e-mail from Keycloak to confirm the address and set a password. The first login
 * accepts the invitation ({@link FirstLogin}).
 */
@Service
class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final IdentityProvider accounts;
    private final RegistrationRepository registrations;
    private final OrgRepository org;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    InvitationService(IdentityProvider accounts, RegistrationRepository registrations, OrgRepository org,
            TenantSession session, TransactionTemplate transactions, Clock clock, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.registrations = registrations;
        this.org = org;
        this.session = session;
        this.transactions = transactions;
        this.clock = clock;
        this.events = events;
    }

    private void requireAccounts() {
        if (!accounts.configured()) {
            throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
        }
    }

    Invitation invite(UUID tenantId, NewInvitation request) {
        requireAccounts();
        String email = RegistrationService.email(request.email());
        transactions.executeWithoutResult(tx -> {
            session.bind(tenantId);
            if (request.employeeId() != null && org.employee(request.employeeId()).isEmpty()) {
                throw OrgProblem.EMPLOYEE_NOT_FOUND.failure();
            }
            if (org.userByEmail(email).isPresent()) {
                throw OrgProblem.EMAIL_TAKEN.failure();
            }
        });
        String accountId;
        try {
            accountId = accounts.create(new IdentityProvider.NewAccount(email, request.firstName().strip(),
                    request.lastName().strip(), tenantId, request.role().name()))
                    .orElseThrow(RegistrationProblem.ACCOUNT_EXISTS::failure);
        } catch (IdentityProvider.Unavailable e) {
            log.warn("Invitation failed: {}", e.getMessage());
            throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
        }
        UUID invitationId = UUID.randomUUID();
        try {
            transactions.executeWithoutResult(tx -> {
                session.bind(tenantId);
                UUID userId = UUID.randomUUID();
                try {
                    org.insertUser(tenantId, userId, new NewUser(request.firstName().strip() + " "
                            + request.lastName().strip(), email, request.role(), request.employeeId()));
                } catch (DuplicateKeyException e) {
                    throw OrgProblem.EMAIL_TAKEN.failure();
                }
                registrations.insertInvitation(invitationId, tenantId, userId, email, request.role(), accountId,
                        clock.instant());
                events.publishEvent(ActionTaken.of(tenantId, "invitation.sent", "invitation", invitationId,
                        ActionTaken.details("role", request.role().name(), "userId", userId,
                                "employeeId", request.employeeId())));
            });
        } catch (RuntimeException e) {
            try {
                accounts.delete(accountId);
            } catch (IdentityProvider.Unavailable left) {
                log.warn("An account of a failed invitation was left in Keycloak: {}", left.getMessage());
            }
            throw e;
        }
        sendSetupEmail(accountId);
        return found(tenantId, invitationId);
    }

    private Invitation found(UUID tenantId, UUID id) {
        return transactions.execute(tx -> {
            session.bind(tenantId);
            return registrations.invitationForUpdate(id)
                    .orElseThrow(RegistrationProblem.INVITATION_NOT_FOUND::failure);
        });
    }

    private void sendSetupEmail(String accountId) {
        try {
            accounts.sendSetupEmail(accountId);
        } catch (IdentityProvider.Unavailable e) {
            log.warn("Invitation e-mail failed: {}", e.getMessage());
            throw RegistrationProblem.SETUP_EMAIL_FAILED.failure();
        }
    }

    List<Invitation> invitations(UUID tenantId) {
        return transactions.execute(tx -> {
            session.bind(tenantId);
            return registrations.invitations();
        });
    }

    private String waitingAccount(UUID tenantId, UUID id) {
        return transactions.execute(tx -> {
            session.bind(tenantId);
            Invitation invitation = registrations.invitationForUpdate(id)
                    .orElseThrow(RegistrationProblem.INVITATION_NOT_FOUND::failure);
            if (invitation.status() != InvitationStatus.sent) {
                throw RegistrationProblem.INVITATION_ACCEPTED.failure();
            }
            return registrations.invitationAccount(id).orElseThrow();
        });
    }

    /** Sends the link again: the old one may have expired. */
    void resend(UUID tenantId, UUID id) {
        requireAccounts();
        sendSetupEmail(waitingAccount(tenantId, id));
    }

    /** Takes back an invitation nobody accepted yet: the account and the user go, the person cannot log in. */
    void revoke(UUID tenantId, UUID id) {
        requireAccounts();
        String accountId = waitingAccount(tenantId, id);
        try {
            accounts.delete(accountId);
        } catch (IdentityProvider.Unavailable e) {
            log.warn("Revoking an invitation failed: {}", e.getMessage());
            throw RegistrationProblem.REGISTRATION_UNAVAILABLE.failure();
        }
        transactions.executeWithoutResult(tx -> {
            session.bind(tenantId);
            Optional<Invitation> invitation = registrations.invitationForUpdate(id);
            invitation.ifPresent(i -> {
                org.deleteUser(i.userId()); // the invitation goes with the user
                events.publishEvent(ActionTaken.of(tenantId, "invitation.revoked", "invitation", id,
                        ActionTaken.details("role", i.role().name())));
            });
        });
    }

    /** Inside a transaction bound to the tenant. True when an invitation to this e-mail was waiting. */
    boolean accept(UUID tenantId, String email) {
        if (!registrations.accept(email, clock.instant())) {
            return false;
        }
        events.publishEvent(ActionTaken.bySystem("registration", tenantId, "invitation.accepted", "tenant", tenantId,
                java.util.Map.of()));
        return true;
    }
}
