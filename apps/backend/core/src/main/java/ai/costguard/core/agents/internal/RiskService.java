package ai.costguard.core.agents.internal;

import ai.costguard.core.agents.AgentRiskChanged;
import ai.costguard.core.agents.AgentRisks;
import ai.costguard.platform.tenant.TenantSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk of agents from the detector (ADR 0031). Every signal is stored by agent and task; the agent row shows the
 * strictest one that is not NORMAL (the agent's own score when all are NORMAL), as the gateway applies the
 * stricter of agent and task. The gateway reacts to the topic by itself; the core keeps it for people.
 */
@Service
class RiskService implements AgentRisks {

    private static final Set<String> ACTIONS = Set.of("NORMAL", "SLOW_DOWN", "REQUIRE_APPROVAL", "STOP");
    private static final int REASON_LENGTH = 1000;

    private record Kept(int score, String action, Instant detectedAt) {
    }

    private record Strictest(int score, String action, List<String> reasons) {
    }

    private final JdbcClient jdbc;
    private final AgentRepository agents;
    private final TenantSession session;
    private final ApplicationEventPublisher events;

    RiskService(JdbcClient jdbc, AgentRepository agents, TenantSession session, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.session = session;
        this.events = events;
    }

    @Override
    @Transactional
    public void signal(UUID tenantId, UUID agentId, String taskId, int score, String action, List<String> reasons,
            Instant detectedAt) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown risk action " + action);
        }
        session.bind(tenantId);
        Optional<Agent> agent = agents.agent(agentId);
        if (agent.isEmpty()) {
            return;
        }
        String task = taskId == null ? "" : taskId;
        int bounded = Math.max(0, Math.min(100, score));
        Optional<Kept> kept = jdbc.sql("""
                select score, action, detected_at from agent_risk where agent_id = ? and task_id = ? for update""")
                .params(agentId, task)
                .query((rs, n) -> new Kept(rs.getInt("score"), rs.getString("action"),
                        rs.getTimestamp("detected_at").toInstant()))
                .optional();
        if (kept.isPresent() && !detectedAt.isAfter(kept.get().detectedAt())) {
            return;
        }
        jdbc.sql("""
                insert into agent_risk (tenant_id, agent_id, task_id, score, action, reasons, detected_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, agent_id, task_id) do update
                set score = excluded.score, action = excluded.action, reasons = excluded.reasons,
                    detected_at = excluded.detected_at""")
                .params(tenantId, agentId, task, bounded, action, reasons.toArray(String[]::new),
                        Timestamp.from(detectedAt))
                .update();
        Strictest top = strictest(agentId);
        String reason = String.join("; ", top.reasons());
        jdbc.sql("update agent set risk_score = ?, risk_reason = ?, risk_action = ? where id = ?")
                .params(top.score(), reason.length() > REASON_LENGTH ? reason.substring(0, REASON_LENGTH) : reason,
                        top.action(), agentId)
                .update();
        String previous = kept.map(Kept::action).orElse("NORMAL");
        if (!previous.equals(action)) {
            Agent a = agent.get();
            events.publishEvent(new AgentRiskChanged(tenantId, agentId, a.name(), a.ownerId(), taskId, bounded, action,
                    previous, List.copyOf(reasons)));
        }
    }

    private Strictest strictest(UUID agentId) {
        return jdbc.sql("""
                select score, action, reasons from agent_risk
                where agent_id = ? and (action <> 'NORMAL' or task_id = '')
                order by case action when 'STOP' then 3 when 'REQUIRE_APPROVAL' then 2 when 'SLOW_DOWN' then 1
                         else 0 end desc, score desc, task_id
                limit 1""")
                .param(agentId)
                .query((rs, n) -> new Strictest(rs.getInt("score"), rs.getString("action"),
                        List.of((String[]) rs.getArray("reasons").getArray())))
                .optional()
                .orElse(new Strictest(0, "NORMAL", List.of()));
    }
}
