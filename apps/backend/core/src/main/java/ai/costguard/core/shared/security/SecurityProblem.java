package ai.costguard.core.shared.security;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum SecurityProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN,
            "This needs a staff role; the role employee reaches only its own cabinet, /api/v1/me (ADR 0051)");

    private final HttpStatus status;
    private final String detail;

    SecurityProblem(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String detail() {
        return detail;
    }
}
