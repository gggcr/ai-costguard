package ai.costguard.core.approvals.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum ApprovalProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Deciding needs the owner, finance or agent_owner role"),
    NOT_AGENT_OWNER(HttpStatus.FORBIDDEN, "An agent owner decides only on their own agents"),
    APPROVAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Approval not found"),
    ALREADY_DECIDED(HttpStatus.CONFLICT, "This request is already closed"),
    NOTARY_HASH_MISMATCH(HttpStatus.CONFLICT, "The notary confirmed a hash the core did not compute");

    private final HttpStatus status;
    private final String detail;

    ApprovalProblem(HttpStatus status, String detail) {
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
