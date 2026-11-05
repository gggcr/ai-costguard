package ai.costguard.gateway.ladder;

public sealed interface StepResult {
    record Pass() implements StepResult {}
    record Refuse(int status, String type) implements StepResult {}
    record RequireApproval(String reason) implements StepResult {}
}