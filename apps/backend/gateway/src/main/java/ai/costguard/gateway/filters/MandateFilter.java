package ai.costguard.gateway.filters;

import ai.costguard.gateway.ladder.CheckStep;
import ai.costguard.gateway.ladder.Operation;
import ai.costguard.gateway.ladder.StepResult;
import ai.costguard.gateway.ladder.Facts;
import ai.costguard.gateway.rules.RuleBook;
import ai.costguard.platform.rules.OperationFacts;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Step 2: the agent's mandate allows the model, vendor, amount and time, and no company rule denies it. */
@Component
public class MandateFilter implements CheckStep {

    private final RuleBook rules;
    private final ZoneId zone;

    public MandateFilter(RuleBook rules, @Value("${costguard.gateway.time-zone:Europe/Moscow}") ZoneId zone) {
        this.rules = rules;
        this.zone = zone;
    }

    @Override
    public Mono<StepResult> check(Operation op) {
        if (op.estimatedCost() == null) {
            return Mono.just(new StepResult.Refuse(403, "price_unknown"));
        }
        OperationFacts facts = Facts.of(op, zoneOf(op));
        if (!rules.mandateAllows(op.agent().getMandateExpression(), facts)) {
            return Mono.just(new StepResult.Refuse(403, "policy_denied"));
        }
        return Mono.just(switch (rules.policies(op.tenantId(), facts)) {
            case DENY -> new StepResult.Refuse(403, "policy_denied");
            case REQUIRE_APPROVAL -> {
                op.needApproval("policy");
                yield new StepResult.Pass();
            }
            case ALLOW -> new StepResult.Pass();
        });
    }

    /** The company's zone, as the core sent it with the agent (ADR 0073); the configured one for an older core. */
    ZoneId zoneOf(Operation op) {
        String named = op.agent().getTimeZone();
        if (named != null && named.contains("/")) {
            try {
                return ZoneId.of(named);
            } catch (java.time.DateTimeException e) {
                return zone;
            }
        }
        return zone;
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
