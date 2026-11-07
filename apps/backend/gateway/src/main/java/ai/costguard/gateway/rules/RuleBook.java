package ai.costguard.gateway.rules;

import ai.costguard.events.PolicyEffect;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.platform.rules.OperationFacts;
import ai.costguard.platform.rules.OperationRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Compiled mandates and company rules. The same evaluator as in the core, so a rule tested there acts the same. */
@Component
public class RuleBook {

    public enum Verdict { ALLOW, REQUIRE_APPROVAL, DENY }

    private record Compiled(long version, List<Map.Entry<OperationRules.Rule, PolicyEffect>> rules) {
    }

    private final Map<String, OperationRules.Rule> mandates = new ConcurrentHashMap<>();
    private final Map<UUID, Compiled> policies = new ConcurrentHashMap<>();

    /** A mandate that does not compile denies: the core checks it, so this only guards against a corrupt record. */
    public boolean mandateAllows(String expression, OperationFacts facts) {
        if (expression == null) {
            return false;
        }
        try {
            return mandates.computeIfAbsent(expression, OperationRules::compile).matches(facts);
        } catch (OperationRules.InvalidRuleException e) {
            return false;
        }
    }

    /** Snapshots can arrive out of order after a restart; an older version never replaces a newer one. */
    public void apply(PolicySnapshot snapshot) {
        List<Map.Entry<OperationRules.Rule, PolicyEffect>> rules = snapshot.getRules().stream()
                .map(r -> Map.entry(OperationRules.compile(r.getExpression()), r.getEffect())).toList();
        policies.merge(snapshot.getTenantId(), new Compiled(snapshot.getVersion(), rules),
                (old, fresh) -> fresh.version() > old.version() ? fresh : old);
    }

    public Verdict policies(UUID tenantId, OperationFacts facts) {
        Compiled compiled = policies.get(tenantId);
        Verdict verdict = Verdict.ALLOW;
        if (compiled == null) {
            return verdict;
        }
        for (var rule : compiled.rules()) {
            if (rule.getKey().matches(facts)) {
                if (rule.getValue() == PolicyEffect.DENY) {
                    return Verdict.DENY;
                }
                if (rule.getValue() == PolicyEffect.REQUIRE_APPROVAL) {
                    verdict = Verdict.REQUIRE_APPROVAL;
                }
            }
        }
        return verdict;
    }
}
