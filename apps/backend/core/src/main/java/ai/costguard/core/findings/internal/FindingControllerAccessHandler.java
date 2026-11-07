package ai.costguard.core.findings.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = FindingController.class)
class FindingControllerAccessHandler {

    private final ProblemHandler problems;

    FindingControllerAccessHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(FindingProblem.ROLE_REQUIRED);
    }
}
