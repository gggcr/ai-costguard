package ai.costguard.gateway.ladder;

import ai.costguard.platform.rules.OperationFacts;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** The facts a mandate or rule sees. Time and weekday are in the configured company time zone. */
public final class Facts {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private Facts() {
    }

    public static OperationFacts of(Operation op, ZoneId zone) {
        ZonedDateTime local = op.at().atZone(zone);
        return new OperationFacts(op.agentId().toString(), op.kind().name(), op.vendor(), op.model(),
                op.estimatedCost().doubleValue(), local.format(HH_MM), local.getDayOfWeek().getValue());
    }
}
