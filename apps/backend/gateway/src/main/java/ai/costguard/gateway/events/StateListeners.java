package ai.costguard.gateway.events;

import ai.costguard.events.AgentRiskSignal;
import ai.costguard.events.AgentState;
import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.ExchangeRate;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.events.TenantPlan;
import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import ai.costguard.gateway.approvals.ApprovalBoard;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.budget.BudgetTree;
import ai.costguard.gateway.keys.AgentDirectory;
import ai.costguard.gateway.plans.PlanBoard;
import ai.costguard.gateway.risk.RiskBoard;
import ai.costguard.gateway.rules.PriceBook;
import ai.costguard.gateway.rules.RateBook;
import ai.costguard.gateway.rules.RuleBook;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every instance reads every state topic from the start, with a group of its own, so each one holds the full
 * state in memory. The topics are compacted, so that read stays short. A tombstone (null value) is skipped, except
 * on agents.state.v1: there it is a company that was erased (ADR 0021), and its agents must stop at once; and on
 * tenants.plans.v1, where it is a company erased or a registration dropped.
 */
@Component
public class StateListeners {

    private static final String GROUP = "gateway-#{T(java.util.UUID).randomUUID()}";

    private final SchemaRegistryClient registry;
    private final AgentDirectory agents;
    private final RuleBook rules;
    private final PriceBook prices;
    private final RateBook rates;
    private final BudgetTree budgets;
    private final BudgetReserve reserve;
    private final RiskBoard risk;
    private final ApprovalBoard approvals;
    private final PlanBoard plans;

    public StateListeners(SchemaRegistryClient registry, AgentDirectory agents, RuleBook rules, PriceBook prices,
            RateBook rates, BudgetTree budgets, BudgetReserve reserve, RiskBoard risk, ApprovalBoard approvals,
            PlanBoard plans) {
        this.plans = plans;
        this.registry = registry;
        this.agents = agents;
        this.rules = rules;
        this.prices = prices;
        this.rates = rates;
        this.budgets = budgets;
        this.reserve = reserve;
        this.risk = risk;
        this.approvals = approvals;
    }

    private <T extends org.apache.avro.specific.SpecificRecord> T read(ConsumerRecord<String, byte[]> r, Class<T> type) {
        return AvroWire.decode(r.value(), registry, type);
    }

    @KafkaListener(topics = "agents.state.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void agents(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            agents.apply(read(r, AgentState.class));
        } else if (r.key() != null) {
            try {
                agents.remove(java.util.UUID.fromString(r.key()));
            } catch (IllegalArgumentException notAnAgent) {
                // a key that is not an agent id names nothing here
            }
        }
    }

    @KafkaListener(topics = "policy.snapshots.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void policies(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            rules.apply(read(r, PolicySnapshot.class));
        }
    }

    @KafkaListener(topics = "catalog.prices.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void prices(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            prices.apply(read(r, CatalogPrice.class));
        }
    }

    /** Rates of the Bank of Russia: a price in another currency than the company's budgets is converted (ADR 0072). */
    @KafkaListener(topics = "catalog.rates.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void rates(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            rates.apply(read(r, ExchangeRate.class));
        }
    }

    /** The tree is in memory; the limit goes to Redis, where every instance shares it. */
    @KafkaListener(topics = "budgets.limits.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void budgets(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            BudgetLimit limit = read(r, BudgetLimit.class);
            budgets.apply(limit);
            reserve.applyLimit(limit.getTenantId(), limit.getBudgetId(), limit.getLimitAmount(), limit.getVersion(),
                    limit.getCorrection(), limit.getLedgerSpent(), limit.getLedgerAsOf()).block();
        }
    }

    @KafkaListener(topics = "agent.risk-signals.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void risk(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            risk.apply(read(r, AgentRiskSignal.class));
        }
    }

    @KafkaListener(topics = "approvals.decisions.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void approvals(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            approvals.apply(read(r, ApprovalDecision.class));
        }
    }

    @KafkaListener(topics = "tenants.plans.v1", groupId = GROUP, containerFactory = "stateListenerFactory")
    void plans(ConsumerRecord<String, byte[]> r) {
        if (r.value() != null) {
            plans.apply(read(r, TenantPlan.class));
        } else if (r.key() != null) {
            try {
                plans.remove(java.util.UUID.fromString(r.key()));
            } catch (IllegalArgumentException notATenant) {
                // a key that is not a tenant id names nothing here
            }
        }
    }
}
