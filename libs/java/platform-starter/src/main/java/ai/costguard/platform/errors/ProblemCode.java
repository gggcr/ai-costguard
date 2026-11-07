package ai.costguard.platform.errors;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** A rule that was broken. Modules implement this with an enum; {@code code} is what clients branch on. */
public interface ProblemCode {

    String name();

    HttpStatus status();

    String detail();

    default String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    default ApiException failure() {
        return new ApiException(this);
    }
}
