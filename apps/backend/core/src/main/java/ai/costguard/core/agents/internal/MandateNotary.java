package ai.costguard.core.agents.internal;

import ai.costguard.core.agents.MandateNotarizations;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records the NEAR transactions of registered mandate versions, from {@code notary.confirmations.v1}. */
@Service
class MandateNotary implements MandateNotarizations {

    private final AgentRepository agents;
    private final TenantSession session;

    MandateNotary(AgentRepository agents, TenantSession session) {
        this.agents = agents;
        this.session = session;
    }

    @Override
    @Transactional
    public void notarized(UUID tenantId, UUID mandateId, int version, String mandateHash, String nearTxHash,
            Instant notarizedAt) {
        session.bind(tenantId);
        String expected = agents.notarizationHash(mandateId, version)
                .orElseThrow(AgentProblem.MANDATE_NOT_FOUND::failure);
        if (!expected.equals(mandateHash)) {
            throw AgentProblem.NOTARY_HASH_MISMATCH.failure();
        }
        agents.notarized(mandateId, version, nearTxHash, notarizedAt);
    }
}
