package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.events.OperationKind;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.budget.BudgetTree;
import ai.costguard.gateway.metering.CostEstimator;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 5: reserve at every budget level. A model call that does not fit is retried with fewer output tokens. */
@Component
public class BudgetFilter implements CheckStep {

    private final BudgetTree tree;
    private final BudgetReserve reserve;
    private final CostEstimator estimator;
    private final long minOutputTokens;

    public BudgetFilter(BudgetTree tree, BudgetReserve reserve, CostEstimator estimator,
            @Value("${costguard.gateway.min-output-tokens:64}") long minOutputTokens) {
        this.tree = tree;
        this.reserve = reserve;
        this.estimator = estimator;
        this.minOutputTokens = minOutputTokens;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        return tree.chain(op.agentId())
                .map(chain -> reserve.reserve(op.tenantId(), op.operationId().toString(), chain, op.estimatedCost())
                        .flatMap(outcome -> decide(op, chain, outcome)))
                .orElseGet(() -> Mono.just(new StepResult.Refuse(402, "budget_exceeded")));
    }

    private Mono<StepResult> decide(Operation op, List<UUID> chain, BudgetReserve.Outcome outcome) {
        return switch (outcome) {
            case BudgetReserve.Outcome.Reserved r -> {
                op.reserved(true);
                yield Mono.just(new StepResult.Pass());
            }
            case BudgetReserve.Outcome.Duplicate d -> Mono.just(new StepResult.Refuse(409, "duplicate_operation"));
            case BudgetReserve.Outcome.Exceeded e -> shrink(op, chain, e.left());
        };
    }

    /** The board's "max_tokens within what is left": cap the output so the call fits, if a useful answer still does. */
    private Mono<StepResult> shrink(Operation op, List<UUID> chain, BigDecimal left) {
        if (op.kind() != OperationKind.LLM_CALL || op.maxOutputTokens() == null) {
            return Mono.just(new StepResult.Refuse(402, "budget_exceeded"));
        }
        var day = op.at().atZone(ZoneOffset.UTC).toLocalDate();
        long affordable = estimator.affordableOutput(op.tenantId(), op.vendor(), op.model(), op.inputTokens(), left, day)
                .orElse(0L);
        if (affordable < minOutputTokens || affordable >= op.maxOutputTokens()) {
            return Mono.just(new StepResult.Refuse(402, "budget_exceeded"));
        }
        BigDecimal cost = estimator.llm(op.tenantId(), op.vendor(), op.model(), op.inputTokens(), affordable, day)
                .orElseThrow();
        op.tokens(op.inputTokens(), affordable);
        op.estimatedCost(cost);
        return reserve.reserve(op.tenantId(), op.operationId().toString(), chain, cost)
                .map(outcome -> {
                    if (outcome instanceof BudgetReserve.Outcome.Reserved) {
                        op.reserved(true);
                        return new StepResult.Pass();
                    }
                    return new StepResult.Refuse(402, "budget_exceeded");
                });
    }

    @Override
    public int getOrder() {
        return 500;
    }
}
