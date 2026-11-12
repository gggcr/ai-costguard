package ai.costguard.core.approvals.internal;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

enum ApprovalStatus { pending, approved, rejected, expired }

enum Verdict { approve, reject }

/**
 * {@code notaryHash} is set for an approved operation large enough to be confirmed in NEAR (ADR 0009), and
 * {@code nearTxHash} once the notary has confirmed it.
 */
record Approval(UUID id, UUID operationId, UUID agentId, String description, BigDecimal amount, String reason,
        Instant createdAt, Instant deadline, ApprovalStatus status, boolean escalated, String decidedBy,
        String comment, String notaryHash, String nearTxHash) {
}

record Decision(@NotNull Verdict verdict, String comment) {
}

/**
 * Who is deciding. {@code restricted} is true for an agent owner, who decides only on their own agents. {@code subject}
 * (the token's sub) finds the decider's account and employee; who decided is written down from that account, never
 * from the token (finding H2 of 2026-11-12).
 */
record Decider(UUID tenantId, boolean restricted, String subject) {
}

record DueApproval(UUID approvalId, UUID tenantId) {
}
