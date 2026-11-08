package ai.costguard.core.agents;

import java.time.Instant;
import java.util.UUID;

/** What the NEAR notary reports back about the mandates it registered. */
public interface MandateNotarizations {

    /**
     * Records the transaction that registered the version. Safe to repeat. A hash that is not the one the core
     * computed is refused with an error and changes nothing.
     */
    void notarized(UUID tenantId, UUID mandateId, int version, String mandateHash, String nearTxHash,
            Instant notarizedAt);
}
