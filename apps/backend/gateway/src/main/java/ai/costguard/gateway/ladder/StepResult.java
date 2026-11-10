package ai.costguard.gateway.ladder;

public sealed interface StepResult {
    record Pass() implements StepResult {}
    /** {@code message}: what the agent's owner should do; the code alone when there is nothing to add. */
    record Refuse(int status, String type, String message) implements StepResult {
        public Refuse(int status, String type) {
            this(status, type, type);
        }
    }
    record RequireApproval(String reason) implements StepResult {}
}
