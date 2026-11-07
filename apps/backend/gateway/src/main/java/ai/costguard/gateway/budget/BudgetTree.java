package ai.costguard.gateway.budget;

import ai.costguard.events.BudgetLevel;
import ai.costguard.events.BudgetLimit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Which budget belongs to an agent and which budgets sit above it, from {@code budgets.limits.v1}. */
@Component
public class BudgetTree {

    record Node(UUID tenantId, UUID budgetId, UUID parentId) {
    }

    private final Map<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> agentBudgets = new ConcurrentHashMap<>();

    public void apply(BudgetLimit limit) {
        nodes.put(limit.getBudgetId(), new Node(limit.getTenantId(), limit.getBudgetId(), limit.getParentBudgetId()));
        if (limit.getLevel() == BudgetLevel.AGENT && limit.getRefId() != null) {
            agentBudgets.put(limit.getRefId(), limit.getBudgetId());
        }
    }

    /** From the agent's budget up to the company. Empty if the agent has none or the chain is broken. */
    public Optional<List<UUID>> chain(UUID agentId) {
        UUID id = agentBudgets.get(agentId);
        if (id == null) {
            return Optional.empty();
        }
        List<UUID> chain = new ArrayList<>();
        while (id != null) {
            Node node = nodes.get(id);
            if (node == null || chain.contains(id) || chain.size() > 10) {
                return Optional.empty();
            }
            chain.add(id);
            id = node.parentId();
        }
        return Optional.of(chain);
    }
}
