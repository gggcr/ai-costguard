package ai.costguard.core.approvals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The gateway held an operation of an agent back for a person (ADR 0067): its owner is asked first in «Мой кабинет».
 * {@code reason}: above_threshold (dearer than the mandate lets the agent spend alone) or risk (the anomaly detector).
 */
public record ApprovalOpened(UUID tenantId, UUID approvalId, UUID agentId, String description, BigDecimal amount,
        String reason, Instant deadline) {
}
