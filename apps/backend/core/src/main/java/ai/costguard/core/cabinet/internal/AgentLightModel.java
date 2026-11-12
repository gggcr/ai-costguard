package ai.costguard.core.cabinet.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** green: in the limit; yellow: 80% spent or the detector is wary; red: the agent is stopped. */
enum Light { green, yellow, red }

/**
 * An agent of mine with its traffic light (ADR 0067). {@code limit}, {@code spent}, {@code percent} and
 * {@code currency} are null when the agent has no budget of its own. {@code why} says in words what the colour means.
 */
record MyAgent(UUID id, String name, String status, String riskAction, int riskScore, String riskReason, Light light,
        String why, BigDecimal limit, BigDecimal spent, Integer percent, String currency, LimitRequest limitRequest,
        List<WaitingOperation> waiting) {
}

/** An operation the gateway held back for me: {@code reason} above_threshold or risk. */
record WaitingOperation(UUID id, String description, BigDecimal amount, String reason, Instant createdAt,
        Instant deadline) {
}

record MyAgents(List<MyAgent> agents, List<LimitRequest> requests) {
}

record NewLimitRequest(@NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal add,
        @NotBlank @Size(max = 1000) String reason) {
}

record LimitDecision(boolean approve, @Size(max = 1000) String comment) {
}

/** {@code currentLimit} and {@code spent}: as they were when the person asked. */
record LimitRequest(UUID id, UUID employeeId, String employeeName, UUID agentId, String agentName, UUID budgetId,
        BigDecimal currentLimit, BigDecimal spent, BigDecimal add, String currency, String reason, String status,
        String decidedBy, Instant decidedAt, String decisionComment, Instant createdAt) {
}

record OperationDecision(boolean approve, @Size(max = 1000) String comment) {
}

/** {@code notaryHash}: what the NEAR notary confirms, when the operation was confirmed. */
record OperationDecided(UUID id, String status, String notaryHash) {
}
