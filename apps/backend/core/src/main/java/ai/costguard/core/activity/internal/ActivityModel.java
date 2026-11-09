package ai.costguard.core.activity.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Who acted. {@code erased} is true once the person was erased: the entries stay, the name and e-mail do not. */
record ActivityActor(UUID id, String name, String email, boolean erased) {
}

/** {@code actor} is null when the system acted; {@code system} then says which part. */
record ActivityEntry(UUID id, Instant at, ActivityActor actor, String system, String action, String targetType,
        UUID targetId, Map<String, Object> details) {
}
