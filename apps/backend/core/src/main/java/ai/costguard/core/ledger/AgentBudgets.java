package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** The budget of one agent, for its owner's traffic light (ADR 0067). Call inside a transaction bound to the tenant. */
public interface AgentBudgets {

    /** The agent's own budget (level agent), if it has one. */
    Optional<AgentBudget> ofAgent(UUID agentId);

    /** Raises the limit by {@code add}, as a change of the limit by finance: the gateway hears it at once. */
    AgentBudget raise(UUID tenantId, UUID budgetId, BigDecimal add);

    /** {@code spent}: what the ledger charged. */
    record AgentBudget(UUID budgetId, BigDecimal limit, BigDecimal spent, String currency) {
    }
}
