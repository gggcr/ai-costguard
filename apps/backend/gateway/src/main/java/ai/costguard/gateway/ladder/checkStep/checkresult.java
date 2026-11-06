packege ai.costguard.gateway.ladder;

public sealed interface StepResult {
    record Pass() interface StepResult {}
    record Refuse(int status, String type) impelents StepResult {}
    record RequireApproval(String reason) impelents StepResult {}
}