package ai.costguard.core.findings.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum FindingProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Working on findings needs the owner, finance, it_admin or agent_owner role"),
    FINDING_NOT_FOUND(HttpStatus.NOT_FOUND, "Finding not found"),
    ACTION_NOT_ALLOWED(HttpStatus.CONFLICT, "This action is not allowed in the current status"),
    DETAIL_REQUIRED(HttpStatus.BAD_REQUEST, "This action needs ownerId, date or reason");

    private final HttpStatus status;
    private final String detail;

    FindingProblem(HttpStatus status, String detail) {
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
