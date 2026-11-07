package ai.costguard.platform.rules;

import java.util.Map;

/**
 * What a rule can see about one operation. The core and the gateway build it the same way, so a rule tested in the
 * core behaves the same at the gateway. {@code time} is "HH:mm" and {@code weekday} is 1 (Monday) to 7, both in the
 * tenant's time zone.
 */
public record OperationFacts(String agentId, String kind, String vendor, String model, double cost, String time,
        long weekday) {

    Map<String, Object> variables() {
        return Map.of("agent_id", agentId, "kind", kind, "vendor", vendor, "model", model == null ? "" : model,
                "cost", cost, "time", time, "weekday", weekday);
    }
}
