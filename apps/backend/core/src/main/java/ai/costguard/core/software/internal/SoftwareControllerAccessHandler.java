package ai.costguard.core.software.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SoftwareController.class)
class SoftwareControllerAccessHandler {

    private final ProblemHandler problems;

    SoftwareControllerAccessHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(SoftwareProblem.ROLE_REQUIRED);
    }
}
