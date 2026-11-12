package ai.costguard.core.ledger.internal;

import ai.costguard.core.ledger.AgentBudgets;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class LedgerAgentBudgets implements AgentBudgets {

    private final LedgerRepository ledger;
    private final LedgerService service;

    LedgerAgentBudgets(LedgerRepository ledger, LedgerService service) {
        this.ledger = ledger;
        this.service = service;
    }

    @Override
    public Optional<AgentBudget> ofAgent(UUID agentId) {
        return ledger.budgetOfAgent(agentId).map(LedgerAgentBudgets::of);
    }

    @Override
    public AgentBudget raise(UUID tenantId, UUID budgetId, BigDecimal add) {
        Budget b = ledger.budget(budgetId).orElseThrow(LedgerProblem.BUDGET_NOT_FOUND::failure);
        return of(service.setLimit(tenantId, budgetId, b.limit().add(add)));
    }

    private static AgentBudget of(Budget b) {
        return new AgentBudget(b.id(), b.limit(), b.spent(), b.currency());
    }
}
