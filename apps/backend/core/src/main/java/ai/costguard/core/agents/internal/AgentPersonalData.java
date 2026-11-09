package ai.costguard.core.agents.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Agents name their owner by employee id only. When the owner is erased the agents stay, so their receipts and
 * spend stay whole, and someone has to take them over. A company erasure also clears the compacted topics that
 * describe its agents: the gateway drops an agent on its tombstone, so its keys stop at once (ADR 0021).
 */
@Component
class AgentPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    AgentPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("mandate", "agent", "virtual_key", "mandate_notarization");
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        long owned = jdbc.sql("select count(*) from agent where owner_id = ?").param(subject.employeeId())
                .query(Long.class).single();
        if (owned > 0) {
            report.manual("agents.agent.owner_id", "Владелец " + owned + " агентов обезличен; назначьте агентам "
                    + "нового владельца");
        }
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        for (UUID agent : jdbc.sql("select id from agent").query(UUID.class).list()) {
            report.tombstone("agents.state.v1", agent.toString());
            report.tombstone("agent.risk-signals.v1", agent.toString());
        }
        TenantRows.delete(jdbc, tenantId, report, "agents", "virtual_key", "mandate_notarization", "agent",
                "mandate");
    }
}
