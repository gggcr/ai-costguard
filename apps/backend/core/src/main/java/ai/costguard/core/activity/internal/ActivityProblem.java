package ai.costguard.core.activity.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum ActivityProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Reading the activity log needs the owner or auditor role"),
    BAD_LIMIT(HttpStatus.BAD_REQUEST, "limit must be between 1 and 200");

    private final HttpStatus status;
    private final String detail;

    ActivityProblem(HttpStatus status, String detail) {
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
