package ai.costguard.core.ledger.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LedgerController.class)
class LedgerProblemHandler {

    private final ProblemHandler problems;

    LedgerProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(LedgerProblem.ROLE_REQUIRED);
    }
}
