package ai.costguard.core.assets.internal;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AssetController.class)
class AssetProblemHandler {

    @ExceptionHandler
    ProblemDetail handle(AssetProblem.Failure failure) {
        AssetProblem problem = failure.problem;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(problem.status, problem.detail);
        body.setProperty("code", problem.code());
        return body;
    }
}
