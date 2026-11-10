package ai.costguard.core.analytics.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AnalyticsController.class)
class AnalyticsControllerAccessHandler {

    private final ProblemHandler problems;

    AnalyticsControllerAccessHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(AnalyticsProblem.ROLE_REQUIRED);
    }
}
