package ai.costguard.platform.errors;

import ai.costguard.platform.tracing.TraceIdSource;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One error format for every service. Works on both MVC and WebFlux: nothing here is stack-specific. */
@RestControllerAdvice
public class ProblemHandler {

    private final TraceIdSource traces;

    public ProblemHandler(TraceIdSource traces) {
        this.traces = traces;
    }

    @ExceptionHandler
    public ProblemDetail handle(ApiException failure) {
        return body(failure.problem());
    }

    /** Also for handlers that map a framework exception to a module's code. */
    public ProblemDetail body(ProblemCode problem) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(problem.status(), problem.detail());
        body.setProperty("code", problem.code());
        traces.current().ifPresent(id -> body.setProperty("trace_id", id));
        return body;
    }
}
