package ai.costguard.gateway.ladder;

import java.math.BigDecimal;

public record OperationContext(
        String operationId,
        String agentId,
        String taskId,
        String kind,
        String vendor,
        String model,
        BigDecimal estimatedCost) {
}