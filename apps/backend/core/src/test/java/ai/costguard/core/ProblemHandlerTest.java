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

    record Numbers() implements ProblemCode {
        @Override
        public String name() {
            return "LIMIT_REACHED";
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.PAYMENT_REQUIRED;
        }

        @Override
        public String detail() {
            return "The plan allows 3 and the company has 3";
        }

        @Override
        public java.util.Map<String, Object> properties() {
            return java.util.Map.of("allowed", 3L, "used", 3L);
        }
    }

    @Test
    void carriesTheNumbersOfAProblemNextToItsCode() {
        ProblemDetail body = new ProblemHandler(Optional::empty).handle(new Numbers().failure());

        assertThat(body.getStatus()).isEqualTo(402);
        assertThat(body.getProperties()).containsEntry("code", "limit_reached").containsEntry("allowed", 3L)
                .containsEntry("used", 3L);
    }
}
