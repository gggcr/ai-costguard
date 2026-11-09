package ai.costguard.core.policies.internal;

import ai.costguard.core.policies.PolicySnapshotPublished;
import ai.costguard.platform.rules.OperationFacts;
import ai.costguard.platform.rules.OperationRules;
import ai.costguard.platform.tenant.TenantSession;
import ai.costguard.core.shared.activity.ActionTaken;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyService {

    private final PolicyRepository policies;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    PolicyService(PolicyRepository policies, TenantSession session, Clock clock, ApplicationEventPublisher events) {
        this.policies = policies;
        this.session = session;
        this.clock = clock;
        this.events = events;
    }

    private static OperationRules.Rule compiled(String expression) {
        try {
            return OperationRules.compile(expression);
        } catch (OperationRules.InvalidRuleException e) {
            throw PolicyProblem.INVALID_EXPRESSION.failure();
        }
    }

    @Transactional(readOnly = true)
    List<PolicyRule> rules(UUID tenantId) {
        session.bind(tenantId);
        return policies.rules();
    }

    @Transactional
    PolicyRule create(UUID tenantId, RuleDetails details) {
        compiled(details.expression());
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            policies.insert(tenantId, id, details, clock.instant());
        } catch (DuplicateKeyException e) {
            throw PolicyProblem.RULE_NAME_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "policy.created", "policy_rule", id, terms(details)));
        return policies.rule(id).orElseThrow();
    }

    @Transactional
    PolicyRule update(UUID tenantId, UUID id, RuleDetails details) {
        compiled(details.expression());
        session.bind(tenantId);
        try {
            if (!policies.update(id, details, clock.instant())) {
                throw PolicyProblem.RULE_NOT_FOUND.failure();
            }
        } catch (DuplicateKeyException e) {
            throw PolicyProblem.RULE_NAME_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "policy.updated", "policy_rule", id, terms(details)));
        return policies.rule(id).orElseThrow();
    }

    private static java.util.Map<String, Object> terms(RuleDetails d) {
        return ActionTaken.details("expression", d.expression(), "effect", d.effect().name(), "enabled",
                d.enabled());
    }

    @Transactional
    void delete(UUID tenantId, UUID id) {
        session.bind(tenantId);
        if (!policies.delete(id)) {
            throw PolicyProblem.RULE_NOT_FOUND.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "policy.deleted", "policy_rule", id, java.util.Map.of()));
    }

    @Transactional(readOnly = true)
    Optional<Publication> publication(UUID tenantId) {
        session.bind(tenantId);
        return policies.publication();
    }

    /** Publishes every enabled rule. Even an empty set is published: it is how a tenant removes its last rule. */
    @Transactional
    Publication publish(UUID tenantId) {
        session.bind(tenantId);
        Instant now = clock.instant();
        long version = policies.nextVersion(tenantId, now);
        List<PolicySnapshotPublished.Rule> rules = policies.rules().stream().filter(PolicyRule::enabled)
                .map(r -> new PolicySnapshotPublished.Rule(r.id(), r.name(), r.expression(), r.effect().name()))
                .toList();
        events.publishEvent(new PolicySnapshotPublished(tenantId, version, rules, now));
        events.publishEvent(ActionTaken.of(tenantId, "policy.published", "company", tenantId,
                java.util.Map.of("version", version, "rules", rules.size())));
        return new Publication(version, now);
    }

    @Transactional(readOnly = true)
    SimulationResult simulate(UUID tenantId, SimulationRequest request) {
        List<RuleDetails> rules = request.rules();
        if (rules == null) {
            session.bind(tenantId);
            rules = policies.rules().stream()
                    .map(r -> new RuleDetails(r.name(), r.expression(), r.effect(), r.enabled())).toList();
        }
        List<RuleDetails> enabled = rules.stream().filter(RuleDetails::enabled).toList();
        List<OperationRules.Rule> programs = enabled.stream().map(r -> compiled(r.expression())).toList();

        List<SimulatedOperation> results = new ArrayList<>();
        int denied = 0;
        int approval = 0;
        for (int i = 0; i < request.operations().size(); i++) {
            SampleOperation op = request.operations().get(i);
            OperationFacts facts = new OperationFacts(op.agentId(), op.kind(), op.vendor(), op.model(), op.cost(),
                    op.time(), op.weekday());
            List<String> matched = new ArrayList<>();
            Outcome outcome = Outcome.ALLOW;
            for (int r = 0; r < programs.size(); r++) {
                if (programs.get(r).matches(facts)) {
                    matched.add(enabled.get(r).name());
                    Outcome effect = enabled.get(r).effect() == Effect.DENY ? Outcome.DENY : Outcome.REQUIRE_APPROVAL;
                    outcome = effect.compareTo(outcome) > 0 ? effect : outcome;
                }
            }
            denied += outcome == Outcome.DENY ? 1 : 0;
            approval += outcome == Outcome.REQUIRE_APPROVAL ? 1 : 0;
            results.add(new SimulatedOperation(i, outcome, matched));
        }
        return new SimulationResult(results.size(), denied, approval, results);
    }
}
