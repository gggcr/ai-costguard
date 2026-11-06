package ai.costguard.core.assets.internal;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AssetController.class)
class AssetProblemHandler {

    @ExceptionHandler
    ProblemDetail handle(AssetProblem.Failure failure) {
        return body(failure.problem);
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied) {
        return body(AssetProblem.ROLE_REQUIRED);
    }

    private static ProblemDetail body(AssetProblem problem) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(problem.status, problem.detail);
        body.setProperty("code", problem.code());
        return body;
    }
}
