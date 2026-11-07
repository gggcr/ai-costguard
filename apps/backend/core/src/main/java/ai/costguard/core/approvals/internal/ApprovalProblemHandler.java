package ai.costguard.core.approvals.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ApprovalController.class)
class ApprovalProblemHandler {

    private final ProblemHandler problems;

    ApprovalProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(ApprovalProblem.ROLE_REQUIRED);
    }
}
