package ai.costguard.gateway.ladder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The goal of the agent's task, from {@code X-CostGuard-Task-Goal} (ADR 0011). A header carries only ASCII, so the
 * SDKs send the goal percent-encoded as UTF-8 ({@code encodeURIComponent}, {@code urllib.parse.quote}); plain ASCII
 * passes as it is. The AI assessor of step 6 reads it; without a goal a person decides.
 */
public final class TaskGoal {

    public static final String HEADER = "X-CostGuard-Task-Goal";
    /** Longer goals are cut: the assessor needs the gist, and the prompt must stay small. */
    public static final int MAX_LENGTH = 1000;

    private TaskGoal() {
    }

    /** The goal, or null when the header is absent or blank. A value that is not valid percent-encoding is kept. */
    public static String fromHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String goal;
        try {
            goal = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            goal = raw;
        }
        goal = goal.strip();
        if (goal.isEmpty()) {
            return null;
        }
        return goal.length() > MAX_LENGTH ? goal.substring(0, MAX_LENGTH) : goal;
    }
}
