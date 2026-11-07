package ai.costguard.core.agents.internal;

import ai.costguard.core.agents.AgentOwners;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class RepositoryAgentOwners implements AgentOwners {

    private final AgentRepository agents;

    RepositoryAgentOwners(AgentRepository agents) {
        this.agents = agents;
    }

    @Override
    public Optional<UUID> ownerOf(UUID agentId) {
        return agents.agent(agentId).map(Agent::ownerId);
    }
}
