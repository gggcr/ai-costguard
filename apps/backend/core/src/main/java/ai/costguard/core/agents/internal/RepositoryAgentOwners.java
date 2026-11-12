package ai.costguard.core.agents.internal;

import ai.costguard.core.agents.AgentOwners;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class RepositoryAgentOwners implements AgentOwners {

    private final AgentRepository agents;
    private final JdbcClient jdbc;

    RepositoryAgentOwners(AgentRepository agents, JdbcClient jdbc) {
        this.agents = agents;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> ownerOf(UUID agentId) {
        return agents.agent(agentId).map(Agent::ownerId);
    }

    @Override
    public List<OwnedAgent> ownedBy(UUID employeeId) {
        return jdbc.sql("""
                select id, name, status, risk_action, risk_score, risk_reason from agent
                where owner_id = ? order by name, id""").param(employeeId)
                .query((rs, i) -> new OwnedAgent(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("status"), rs.getString("risk_action"), rs.getInt("risk_score"),
                        rs.getString("risk_reason")))
                .list();
    }
}
