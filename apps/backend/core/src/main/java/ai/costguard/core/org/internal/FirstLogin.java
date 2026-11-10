package ai.costguard.core.org.internal;

import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.platform.tenant.TenantSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The first request of a person whose token says the e-mail is confirmed (ADR 0032): a pending company becomes
 * active, and a waiting invitation to that e-mail is accepted. Keycloak issues no token before the e-mail is
 * confirmed and a password set, so the token is the proof. Each instance remembers whom it has seen, so later
 * requests cost nothing.
 */
@Component
class FirstLogin implements HandlerInterceptor {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final RegistrationService registrations;
    private final InvitationService invitations;
    private final TenantSession session;
    private final TransactionTemplate transactions;

    FirstLogin(RegistrationService registrations, InvitationService invitations, TenantSession session,
            TransactionTemplate transactions) {
        this.registrations = registrations;
        this.invitations = invitations;
        this.session = session;
        this.transactions = transactions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)
                || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                || jwt.getClaimAsString("email") == null || jwt.getClaimAsString("tenant_id") == null) {
            return true;
        }
        UUID tenantId = TenantClaim.of(jwt);
        String email = jwt.getClaimAsString("email").strip().toLowerCase(Locale.ROOT);
        String key = tenantId + ":" + email;
        if (!seen.contains(key)) {
            transactions.executeWithoutResult(tx -> {
                session.bind(tenantId);
                registrations.activate(tenantId);
                invitations.accept(tenantId, email);
            });
            seen.add(key);
        }
        return true;
    }
}
