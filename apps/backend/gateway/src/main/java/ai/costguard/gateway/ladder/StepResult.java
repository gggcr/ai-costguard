package ai.costguard.gateway.ladder;

import ai.costguard.gateway.answers.Answer;

public sealed interface StepResult {
    record Pass() implements StepResult {}
    /** {@code message}: what the agent's owner should do; the code alone when there is nothing to add. */
    record Refuse(int status, String type, String message) implements StepResult {
        public Refuse(int status, String type) {
            this(status, type, type);
        }
    }
    record RequireApproval(String reason) implements StepResult {}
    /** A retry of an operation that went through: the agent gets its first answer again (step 3). */
    record Answered(Answer answer) implements StepResult {}
}
