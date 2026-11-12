package ai.costguard.core.org.internal;

import ai.costguard.core.shared.activity.ActionTaken;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Links a Keycloak account (the token's sub) to the CostGuard account of its person, once. Accounts the core creates
 * together with the Keycloak account (invitation, onboarding, registration) are linked at creation; this covers the
 * others: an account added by hand ({@code POST /api/v1/users}) and older ones the migration could not link.
 *
 * <p>The only proof taken is an e-mail Keycloak marked as confirmed ({@code email_verified}): Keycloak resets that
 * mark whenever a person changes the address themselves, so an address put on someone else's account never links.
 * The address must name exactly one account of the company. After the link the e-mail of the token no longer
 * matters.
 *
 * <p>It never links an account to an employee. Addresses of employees are edited by an it_admin, so an employee link
 * made from them at a sign-in would let an it_admin put their own address on someone and take that person's cabinet
 * (finding H1 of 2026-11-12). The employee of an account comes only from an invitation, an onboarding, the owner
 * adding the user, or the one-time fill of V47.
 */
@Component
class LoginLinks {

    private final OrgRepository org;
    private final ApplicationEventPublisher events;

    LoginLinks(OrgRepository org, ApplicationEventPublisher events) {
        this.org = org;
        this.events = events;
    }

    /**
     * Call inside a transaction bound to the tenant, only for a token whose e-mail is confirmed. True when the Keycloak
     * account has its CostGuard account, linked before or now.
     */
    boolean link(UUID tenantId, String subject, String confirmedEmail) {
        if (org.userBySubject(subject).isPresent()) {
            return true;
        }
        List<UserAccount> unlinked = org.unlinkedUsersByEmail(confirmedEmail);
        if (unlinked.size() != 1 || !org.linkSubject(unlinked.get(0).id(), subject)) {
            return false;
        }
        events.publishEvent(ActionTaken.of(tenantId, "user.login_linked", "user", unlinked.get(0).id(), Map.of()));
        return true;
    }
}
