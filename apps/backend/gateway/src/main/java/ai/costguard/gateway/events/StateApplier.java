package ai.costguard.gateway.events;

import ai.costguard.events.AgentRiskSignal;
import ai.costguard.events.AgentState;
import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.ExchangeRate;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.events.TenantPlan;
import ai.costguard.gateway.approvals.ApprovalBoard;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.budget.BudgetTree;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.plans.PlanBoard;
import ai.costguard.gateway.risk.RiskBoard;
import ai.costguard.gateway.rules.PriceBook;
import ai.costguard.gateway.rules.RateBook;
import ai.costguard.gateway.rules.RuleBook;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/**
 * What the gateway does with each record of the state topics, whichever way it came: from Kafka
 * ({@link StateListeners}) or from the core's feed ({@link CoreFeed}, ADR 0080). A tombstone (null value) is skipped,
 * except on agents.state.v1: there it is a company that was erased (ADR 0021), and its agents must stop at once; and
 * on tenants.plans.v1, where it is a company erased or a registration dropped.
 */
@Component
public class StateApplier {

    /** The record class of each topic the gateway reads. */
    static final Map<String, Class<? extends SpecificRecord>> TYPES = Map.of(
            "agents.state.v1", AgentState.class,
            "policy.snapshots.v1", PolicySnapshot.class,
            "catalog.prices.v1", CatalogPrice.class,
            "catalog.rates.v1", ExchangeRate.class,
            "budgets.limits.v1", BudgetLimit.class,
            "agent.risk-signals.v1", AgentRiskSignal.class,
            "approvals.decisions.v1", ApprovalDecision.class,
            "tenants.plans.v1", TenantPlan.class);

    private final AgentDirectory agents;
    private final RuleBook rules;
    private final PriceBook prices;
    private final RateBook rates;
    private final BudgetTree budgets;
    private final BudgetReserve reserve;
    private final RiskBoard risk;
    private final ApprovalBoard approvals;
    private final PlanBoard plans;

    public StateApplier(AgentDirectory agents, RuleBook rules, PriceBook prices, RateBook rates, BudgetTree budgets,
            BudgetReserve reserve, RiskBoard risk, ApprovalBoard approvals, PlanBoard plans) {
        this.agents = agents;
        this.rules = rules;
        this.prices = prices;
        this.rates = rates;
        this.budgets = budgets;
        this.reserve = reserve;
        this.risk = risk;
        this.approvals = approvals;
        this.plans = plans;
    }

    /**
     * Applies one record; {@code decode} turns the value into the topic's record and is not called for a tombstone.
     * A topic the gateway does not read is ignored.
     */
    public void apply(String topic, String key, byte[] value,
            Function<Class<? extends SpecificRecord>, SpecificRecord> decode) {
        Class<? extends SpecificRecord> type = TYPES.get(topic);
        if (type == null) {
            return;
        }
        if (value == null) {
            tombstone(topic, key);
            return;
        }
        SpecificRecord record = decode.apply(type);
        switch (record) {
            case AgentState a -> agents.apply(a);
            case PolicySnapshot p -> rules.apply(p);
            case CatalogPrice p -> prices.apply(p);
            // a price in another currency than the company's budgets is converted (ADR 0072)
            case ExchangeRate r -> rates.apply(r);
            case BudgetLimit limit -> {
                // the tree is in memory; the limit goes to Redis, where every instance shares it
                budgets.apply(limit);
                reserve.applyLimit(limit.getTenantId(), limit.getBudgetId(), limit.getLimitAmount(),
                        limit.getVersion(), limit.getCorrection(), limit.getLedgerSpent(), limit.getLedgerAsOf())
                        .block();
            }
            case AgentRiskSignal s -> risk.apply(s);
            case ApprovalDecision d -> approvals.apply(d);
            case TenantPlan p -> plans.apply(p);
            default -> throw new IllegalStateException("No handler for " + record.getClass().getName());
        }
    }

    private void tombstone(String topic, String key) {
        if (key == null) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException notAnId) {
            return; // a key that is not an agent or tenant id names nothing here
        }
        switch (topic) {
            case "agents.state.v1" -> agents.remove(id);
            case "tenants.plans.v1" -> plans.remove(id);
            default -> {
                // other topics keep their state: compaction drops the record, nothing to stop
            }
        }
    }
}
