package ai.costguard.core.agents;

import java.time.Instant;
import java.util.UUID;

/**
 * A mandate version was published. {@code mandateHash} is the sha256 of the terms in force (ADR 0009); the NEAR
 * notary registers it, so anyone can later check which terms an agent ran under.
 */
public record MandatePublished(UUID tenantId, UUID mandateId, int version, String mandateHash, Instant publishedAt) {
}
