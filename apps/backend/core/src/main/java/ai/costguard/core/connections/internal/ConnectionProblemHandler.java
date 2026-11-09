package ai.costguard.core.connections.internal;

import ai.costguard.platform.errors.ProblemHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConnectionController.class)
class ConnectionProblemHandler {

    private final ProblemHandler problems;

    ConnectionProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler
    ProblemDetail handle(AccessDeniedException denied, HttpServletRequest request) {
        return problems.body(request.getRequestURI().endsWith("/agent-secrets")
                ? ConnectionProblem.CONNECTOR_ROLE_REQUIRED : ConnectionProblem.ROLE_REQUIRED);
    }
}
