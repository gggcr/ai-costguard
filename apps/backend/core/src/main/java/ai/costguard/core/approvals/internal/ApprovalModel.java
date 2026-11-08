package ai.costguard.core.approvals.internal;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

enum ApprovalStatus { pending, approved, rejected, expired }

enum Verdict { approve, reject }

record Approval(UUID id, UUID operationId, UUID agentId, String description, BigDecimal amount, String reason,
        Instant createdAt, Instant deadline, ApprovalStatus status, boolean escalated, String decidedBy,
        String comment) {
}

record Decision(@NotNull Verdict verdict, String comment) {
}

/** Who is deciding. {@code restricted} is true for an agent owner, who decides only on their own agents. */
record Decider(UUID tenantId, boolean restricted, String email) {
}

record DueApproval(UUID approvalId, UUID tenantId) {
}
