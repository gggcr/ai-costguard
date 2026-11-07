package ai.costguard.core.policies.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum PolicyProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing policies needs the owner or it_admin role"),
    RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Rule not found"),
    RULE_NAME_TAKEN(HttpStatus.CONFLICT, "A rule with this name exists"),
    INVALID_EXPRESSION(HttpStatus.BAD_REQUEST, "The expression is not valid CEL over the operation variables");

    private final HttpStatus status;
    private final String detail;

    PolicyProblem(HttpStatus status, String detail) {
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
