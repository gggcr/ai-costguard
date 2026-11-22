package ai.costguard.core.agents;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the gateway must know about an agent. Published after every change, in full, because the topic it feeds
 * is compacted. {@code mandateVersion} is null while the mandate was never published: the gateway denies then.
 * {@code timeZone} is the company's (ADR 0073): the hours of the mandate are its hours.
 */
public record AgentStateChanged(
        UUID tenantId,
        UUID agentId,
        List<String> virtualKeyHashes,
        boolean suspended,
        Integer mandateVersion,
        String mandateExpression,
        BigDecimal approvalThreshold,
        Instant updatedAt,
        String timeZone) {
}
