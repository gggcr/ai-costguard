package ai.costguard.platform.errors;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A rule that was broken. Modules implement this with an enum; {@code code} is what clients branch on. */
public interface ProblemCode {

    String name();

    HttpStatus status();

    String detail();

    default String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Extra members of the problem body, for a client that shows numbers rather than parsing the detail. */
    default Map<String, Object> properties() {
        return Map.of();
    }

    default ApiException failure() {
        return new ApiException(this);
    }
}
