package ai.costguard.core.ledger.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

enum LedgerProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing budgets needs the owner or finance role"),
    BUDGET_NOT_FOUND(HttpStatus.NOT_FOUND, "Budget not found"),
    PARENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Parent budget not found"),
    INVALID_HIERARCHY(HttpStatus.BAD_REQUEST, "A company budget has no parent; a department sits under the company and an agent under a department"),
    BUDGET_EXISTS(HttpStatus.CONFLICT, "This company, department or agent already has a budget");

    private final HttpStatus status;
    private final String detail;

    LedgerProblem(HttpStatus status, String detail) {
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
