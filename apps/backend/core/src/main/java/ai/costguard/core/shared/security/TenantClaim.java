package ai.costguard.core.shared.security;

import ai.costguard.platform.errors.ProblemCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** The tenant comes only from the verified token, never from the request. */
public final class TenantClaim {

    private enum Problem implements ProblemCode {
        TENANT_MISSING;

        @Override
        public HttpStatus status() {
            return HttpStatus.FORBIDDEN;
        }

        @Override
        public String detail() {
            return "Token has no valid tenant_id claim";
        }
    }

    private TenantClaim() {
    }

    public static UUID of(Jwt jwt) {
        String claim = jwt.getClaimAsString("tenant_id");
        if (claim == null) {
            throw Problem.TENANT_MISSING.failure();
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            throw Problem.TENANT_MISSING.failure();
        }
    }
}
