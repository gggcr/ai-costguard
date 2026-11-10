package ai.costguard.core.plans.internal;

import ai.costguard.core.shared.security.TenantClaim;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * After the trial or the paid period ends, a company can read everything and change nothing (ADR 0037): every
 * POST, PUT, PATCH and DELETE of its users is refused with 402 plan_read_only. Paying for a plan stays open, and so
 * does the privacy section: a company that leaves must be able to ask for its data to be erased.
 *
 * <p>The failure thrown here reaches the shared problem handler like one thrown by a controller.
 */
@Component
class ReadOnlyGuard implements HandlerInterceptor {

    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final List<String> OPEN = List.of("/api/v1/billing/", "/api/v1/privacy/",
            "/api/v1/registrations");

    private final PlanService plans;

    ReadOnlyGuard(PlanService plans) {
        this.plans = plans;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!CHANGES.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (OPEN.stream().anyMatch(path::startsWith)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getClaimAsString("tenant_id") == null) {
            return true;
        }
        if (plans.readOnly(TenantClaim.of(jwt))) {
            throw PlanProblem.PLAN_READ_ONLY.failure();
        }
        return true;
    }
}
