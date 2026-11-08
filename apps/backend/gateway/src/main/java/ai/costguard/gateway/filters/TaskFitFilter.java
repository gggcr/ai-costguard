package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.approvals.TaskFitAssessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 6: above the mandate threshold the AI assessor decides; if it does not say yes, a human does. */
@Component
public class TaskFitFilter implements CheckStep {

    private final TaskFitAssessor assessor;

    public TaskFitFilter(TaskFitAssessor assessor) {
        this.assessor = assessor;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        var threshold = op.agent().getApprovalThreshold();
        if (threshold == null || op.estimatedCost().compareTo(threshold) <= 0) {
            return Mono.just(new StepResult.Pass());
        }
        return assessor.fits(op).map(fits -> {
            if (!fits) {
                op.needApproval("above_threshold");
            }
            return new StepResult.Pass();
        });
    }

    @Override
    public int getOrder() {
        return 600;
    }
}
