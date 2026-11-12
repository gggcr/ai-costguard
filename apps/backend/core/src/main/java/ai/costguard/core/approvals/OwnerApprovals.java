package ai.costguard.core.approvals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner of an agent confirms or blocks the operations the gateway held back (ADR 0067). A confirmation goes to
 * the NEAR notary as hashes, whatever the amount; a block stays in the activity log. Call inside a transaction bound
 * to the tenant.
 */
public interface OwnerApprovals {

    /** Operations of these agents that still wait, newest first. */
    List<Waiting> waiting(Collection<UUID> agentIds);

    /**
     * Decides as the owner. {@code deciderAccountId}: the CostGuard account of the token's subject, which the decision
     * names (decided_by and the approver hash come from it, never from the token, finding H2 of 2026-11-12). Empty
     * when the request is not of an agent of {@code ownerId}; throws {@link AlreadyDecided} when someone decided first
     * or the time is over.
     */
    Optional<Decided> decide(UUID tenantId, UUID approvalId, UUID ownerId, UUID deciderAccountId, boolean approve,
            String comment);

    record Waiting(UUID id, UUID agentId, String description, BigDecimal amount, String reason, Instant createdAt,
            Instant deadline) {
    }

    /** {@code notaryHash}: what NEAR confirms, for a confirmation. */
    record Decided(UUID id, String status, String notaryHash) {
    }

    final class AlreadyDecided extends RuntimeException {

        public AlreadyDecided() {
            super("already decided");
        }
    }
}
