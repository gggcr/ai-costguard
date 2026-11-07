package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.platform.errors.ProblemCode;
import ai.costguard.platform.errors.ProblemHandler;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemHandlerTest {

    enum Sample implements ProblemCode {
        THING_NOT_FOUND;

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String detail() {
            return "Thing not found";
        }
    }

    @Test
    void carriesTheCodeAndTheTrace() {
        ProblemDetail body = new ProblemHandler(() -> Optional.of("abc123")).handle(Sample.THING_NOT_FOUND.failure());

        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getDetail()).isEqualTo("Thing not found");
        assertThat(body.getProperties()).containsEntry("code", "thing_not_found").containsEntry("trace_id", "abc123");
    }

    @Test
    void leavesTheTraceOutWhenTracingIsOff() {
        ProblemDetail body = new ProblemHandler(Optional::empty).handle(Sample.THING_NOT_FOUND.failure());

        assertThat(body.getProperties()).containsOnlyKeys("code");
    }
}
