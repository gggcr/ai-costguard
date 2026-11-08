package ai.costguard.platform.tracing;

import java.util.Optional;

/** The trace of the current request, so a client can quote it in a support ticket. */
@FunctionalInterface
public interface TraceIdSource {

    Optional<String> current();
}
