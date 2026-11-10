package ai.costguard.gateway.ladder;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.OperationDecision;
import ai.costguard.gateway.budget.BudgetReserve;
import ai.costguard.gateway.events.OperationLog;
import ai.costguard.gateway.plans.PlanGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs the ladder and makes its outcome stick: a refused or held operation gets its reserve back, and every
 * outcome is recorded. A step that fails becomes 503 check_unavailable, never a pass. An operation that took a
 * place in the plan's monthly quota gives it back when it does not pass (ADR 0037).
 */
@Component
public class Decisions {

    private final CheckLadder ladder;
    private final BudgetReserve reserve;
    private final OperationLog log;
    private final PlanGate plans;

    public Decisions(CheckLadder ladder, BudgetReserve reserve, OperationLog log, PlanGate plans) {
        this.ladder = ladder;
        this.reserve = reserve;
        this.log = log;
        this.plans = plans;
    }

    public Mono<StepResult> decide(Operation op) {
        return ladder.run(op)
                .onErrorResume(e -> Mono.just(new StepResult.Refuse(503, "check_unavailable")))
                .flatMap(result -> {
                    if (result instanceof StepResult.Pass) {
                        return Mono.just(result);
                    }
                    Mono<Boolean> release = op.reserved() ? reserve.release(op.tenantId(), op.operationId().toString())
                            : Mono.just(false);
                    return release.onErrorReturn(false)
                            .flatMap(x -> plans.giveBack(op).onErrorComplete().thenReturn(x)).doOnNext(x -> {
                        op.reserved(false);
                        record(op, result);
                    }).thenReturn(result);
                });
    }

    private void record(Operation op, StepResult result) {
        if (op.agent() == null) {
            return;
        }
        if (result instanceof StepResult.RequireApproval held) {
            log.record(event(op, OperationDecision.PENDING_APPROVAL, held.reason(), null));
        } else if (result instanceof StepResult.Refuse refused) {
            log.record(event(op, OperationDecision.DENIED, refused.type(), null));
        }
    }

    /**
     * Called when the provider or the paid API failed after the operation passed: nothing is charged, so the
     * operation is recorded as denied with this code and an actual cost of zero (ADR 0010).
     */
    public void failed(Operation op, String code) {
        if (op.agent() != null) {
            log.record(event(op, OperationDecision.DENIED, code, BigDecimal.ZERO));
        }
    }

    /** Called once the actual cost is known. */
    public void allowed(Operation op, BigDecimal actual, Long inputTokens, Long outputTokens) {
        AgentOperation e = event(op, OperationDecision.ALLOWED, null, actual);
        e.setInputTokens(inputTokens);
        e.setOutputTokens(outputTokens);
        log.record(e);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(6, RoundingMode.HALF_EVEN);
    }

    private static AgentOperation event(Operation op, OperationDecision decision, String code, BigDecimal actual) {
        return AgentOperation.newBuilder()
                .setOperationId(op.operationId()).setTenantId(op.tenantId()).setAgentId(op.agentId())
                .setTaskId(op.taskId()).setKind(op.kind()).setProvider(op.vendor()).setModel(op.model())
                .setDecision(decision).setDenialCode(code)
                .setEstimatedCost(money(op.estimatedCost() == null ? BigDecimal.ZERO : op.estimatedCost()))
                .setActualCost(money(actual)).setCurrency("RUB").setInputTokens(op.inputTokens())
                .setOutputTokens(op.maxOutputTokens()).setMandateVersion(op.agent().getMandateVersion())
                .setPromptSimhash(null).setOccurredAt(op.at()).build();
    }
}
