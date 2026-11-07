package ai.costguard.core.assets.internal;

import ai.costguard.platform.errors.ProblemHandler;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only the role message is specific to this module; other errors go through {@link ProblemHandler}. */
@RestControllerAdvice(assignableTypes = AssetController.class)
class AssetProblemHandler {

    private final ProblemHandler problems;

    AssetProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return problems.body(AssetProblem.ROLE_REQUIRED);
    }
}
