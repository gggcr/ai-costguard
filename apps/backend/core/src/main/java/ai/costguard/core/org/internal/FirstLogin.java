package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.platform.tenant.TenantSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The first request of a person whose token says the e-mail is confirmed (ADR 0036): a pending company becomes
 * active, a waiting invitation of that Keycloak account is accepted, and an account added without one is linked to
 * it ({@link LoginLinks}). Keycloak issues no token before the e-mail is confirmed and a password set, so the token is
 * the proof. The person is keyed by the token's subject, never by its e-mail. Each instance remembers whom it has
 * linked, so later requests cost nothing; a person with nothing to link yet is looked at again after a minute.
 */
@Component
class FirstLogin implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FirstLogin.class);
    private static final Duration RECHECK = Duration.ofMinutes(1);
    private static final Instant DONE = Instant.MAX;

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final RegistrationService registrations;
    private final InvitationService invitations;
    private final LoginLinks links;
    private final TenantSession session;
    private final TransactionTemplate transactions;
    private final Clock clock;

    FirstLogin(RegistrationService registrations, InvitationService invitations, LoginLinks links,
            TenantSession session, TransactionTemplate transactions, Clock clock) {
        this.registrations = registrations;
        this.invitations = invitations;
        this.links = links;
        this.session = session;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)
                || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                || jwt.getSubject() == null || jwt.getSubject().isBlank()
                || jwt.getClaimAsString("email") == null || jwt.getClaimAsString("tenant_id") == null) {
            return true;
        }
        UUID tenantId = TenantClaim.of(jwt);
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email").strip().toLowerCase(Locale.ROOT);
        String key = tenantId + ":" + subject;
        Instant now = clock.instant();
        Instant next = seen.get(key);
        if (next != null && now.isBefore(next)) {
            return true;
        }
        transactions.executeWithoutResult(tx -> {
            session.bind(tenantId);
            registrations.activate(tenantId);
            invitations.accept(tenantId, subject);
        });
        boolean complete;
        try {
            // its own transaction: a refused link must not undo the activation above
            complete = Boolean.TRUE.equals(transactions.execute(tx -> {
                session.bind(tenantId);
                return links.link(tenantId, subject, email);
            }));
        } catch (DataIntegrityViolationException e) {
            // the subject is linked to an account of another company: nothing is linked here
            log.warn("A Keycloak account was not linked: its subject belongs to another CostGuard account");
            complete = false;
        }
        seen.put(key, complete ? DONE : now.plus(RECHECK));
        return true;
    }
}
