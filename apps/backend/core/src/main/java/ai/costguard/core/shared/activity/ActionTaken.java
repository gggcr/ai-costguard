package ai.costguard.core.shared.activity;

import java.util.Map;
import java.util.UUID;

/**
 * Something a person or the system changed, for the activity log (ADR 0022). Publish it in the transaction of the
 * change: the log writes it in that same transaction, so a change cannot happen without its entry. Who acted comes
 * from the authenticated request; {@code system} names the actor when there is no request (a connector, a timer).
 *
 * <p>{@code details} carries ids, amounts, roles and codes only. A name or an e-mail would stay in a log nobody can
 * edit; point at the person by id instead.
 */
public record ActionTaken(UUID tenantId, String action, String targetType, UUID targetId, Map<String, ?> details,
        String system) {

    /** A detail without a value is left out. */
    public ActionTaken {
        Map<String, Object> kept = new java.util.TreeMap<>();
        if (details != null) {
            details.forEach((k, v) -> {
                if (v != null) {
                    kept.put(k, v);
                }
            });
        }
        details = java.util.Collections.unmodifiableMap(kept);
    }

    /** Details from pairs of name and value; unlike {@code Map.of} a value may be null, and is then left out. */
    public static Map<String, Object> details(Object... namesAndValues) {
        Map<String, Object> out = new java.util.TreeMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                out.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
        }
        return out;
    }

    public static ActionTaken of(UUID tenantId, String action, String targetType, UUID targetId,
            Map<String, ?> details) {
        return new ActionTaken(tenantId, action, targetType, targetId, details, null);
    }

    public static ActionTaken bySystem(String system, UUID tenantId, String action, String targetType, UUID targetId,
            Map<String, ?> details) {
        return new ActionTaken(tenantId, action, targetType, targetId, details, system);
    }
}
