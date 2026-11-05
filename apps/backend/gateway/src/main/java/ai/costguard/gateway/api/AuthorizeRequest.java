package ai.costguard.gateway.api;

import java.math.BigDecimal;

/** Body of POST /v1/spend/authorize. The agent is not in the body: it comes from the virtual key. */
public record AuthorizeRequest(
        String operationId,
        String taskId,
        String kind,
        String vendor,
        BigDecimal amount) {
}
