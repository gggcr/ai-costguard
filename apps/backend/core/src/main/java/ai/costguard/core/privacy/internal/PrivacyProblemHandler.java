package ai.costguard.core.privacy.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PrivacyController.class)
class PrivacyProblemHandler {

    private final ProblemHandler problems;

    PrivacyProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(PrivacyProblem.ROLE_REQUIRED);
    }

    @ExceptionHandler
    ProblemDetail handle(KeycloakAccounts.Unavailable down) {
        return problems.body(PrivacyProblem.IDENTITY_PROVIDER_UNAVAILABLE);
    }
}
