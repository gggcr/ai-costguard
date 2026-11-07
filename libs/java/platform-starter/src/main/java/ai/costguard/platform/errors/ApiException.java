package ai.costguard.platform.errors;

/** Ends a request with the RFC 9457 body of its {@link ProblemCode}. */
public class ApiException extends RuntimeException {

    private final transient ProblemCode problem;

    public ApiException(ProblemCode problem) {
        super(problem.detail());
        this.problem = problem;
    }

    public ProblemCode problem() {
        return problem;
    }
}
