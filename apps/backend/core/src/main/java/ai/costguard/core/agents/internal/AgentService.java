package ai.costguard.core.agents.internal;

import ai.costguard.core.plans.Limit;
import ai.costguard.core.plans.PlanLimits;
import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.core.agents.MandatePublished;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.platform.tenant.TenantSession;
import ai.costguard.core.shared.activity.ActionTaken;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AgentService {

    private final AgentRepository agents;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final EmployeeDirectory directory;
    private final PlanLimits plans;

    AgentService(AgentRepository agents, TenantSession session, Clock clock, ApplicationEventPublisher events,
            EmployeeDirectory directory, PlanLimits plans) {
        this.plans = plans;
        this.agents = agents;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    List<Agent> list(UUID tenantId) {
        session.bind(tenantId);
        return agents.agents();
    }

    @Transactional(readOnly = true)
    Agent get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return found(id);
    }

    private Agent found(UUID id) {
        return agents.agent(id).orElseThrow(AgentProblem.AGENT_NOT_FOUND::failure);
    }

    /** Takes the agent's row lock and checks that the caller may change it. */
    private Agent changing(Actor actor, UUID id) {
        session.bind(actor.tenantId());
        Agent agent = agents.agentForUpdate(id).orElseThrow(AgentProblem.AGENT_NOT_FOUND::failure);
        requireOwnership(actor, agent.ownerId());
        return agent;
    }

    private void requireOwnership(Actor actor, UUID ownerId) {
        if (actor.restricted() && !directory.employeeOfUser(actor.email()).filter(ownerId::equals).isPresent()) {
            throw AgentProblem.NOT_AGENT_OWNER.failure();
        }
    }

    private Mandate mandate(UUID id) {
        return agents.mandate(id).orElseThrow(AgentProblem.MANDATE_NOT_FOUND::failure);
    }

    @Transactional
    Agent create(Actor actor, AgentDetails details) {
        session.bind(actor.tenantId());
        requireOwnership(actor, details.ownerId());
        mandate(details.mandateId());
        plans.requireRoom(actor.tenantId(), Limit.AGENTS, agents::count);
        UUID id = UUID.randomUUID();
        agents.insertAgent(actor.tenantId(), id, details, clock.instant());
        Agent created = found(id);
        publish(actor.tenantId(), created);
        events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.created", "agent", id,
                Map.of("mandateId", details.mandateId(), "ownerId", details.ownerId())));
        return created;
    }

    @Transactional
    Agent update(Actor actor, UUID id, AgentDetails details) {
        changing(actor, id);
        requireOwnership(actor, details.ownerId());
        mandate(details.mandateId());
        agents.updateAgent(id, details);
        Agent updated = found(id);
        publish(actor.tenantId(), updated);
        events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.updated", "agent", id,
                Map.of("mandateId", details.mandateId(), "ownerId", details.ownerId())));
        return updated;
    }

    /** Stops the agent at once: the gateway answers 423 from the next state event on. */
    @Transactional
    Agent setStatus(Actor actor, UUID id, AgentStatus status) {
        Agent agent = changing(actor, id);
        if (agent.status() == status) {
            return agent;
        }
        agents.setStatus(id, status);
        Agent changed = found(id);
        publish(actor.tenantId(), changed);
        events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.status_changed", "agent", id,
                Map.of("from", agent.status().name(), "to", status.name())));
        return changed;
    }

    /** The gateway learns of a deleted agent as a suspended one with no keys. */
    @Transactional
    void delete(Actor actor, UUID id) {
        Agent agent = changing(actor, id);
        agents.deleteAgent(id);
        events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.deleted", "agent", id, Map.of()));
        events.publishEvent(new AgentStateChanged(actor.tenantId(), agent.id(), List.of(), true, null, null, null,
                clock.instant()));
    }

    @Transactional(readOnly = true)
    List<VirtualKey> keys(UUID tenantId, UUID agentId) {
        session.bind(tenantId);
        found(agentId);
        return agents.keys(agentId);
    }

    @Transactional
    IssuedKey issueKey(Actor actor, UUID agentId) {
        Agent agent = changing(actor, agentId);
        String secret = VirtualKeySecrets.generate();
        VirtualKey key = new VirtualKey(UUID.randomUUID(), agentId, VirtualKeySecrets.prefix(secret), clock.instant(),
                KeyStatus.active);
        agents.insertKey(actor.tenantId(), key, VirtualKeySecrets.hash(secret));
        publish(actor.tenantId(), agent);
        events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.key_issued", "agent", agentId,
                Map.of("keyId", key.id(), "prefix", key.prefix())));
        return new IssuedKey(key, secret);
    }

    @Transactional
    void revokeKey(Actor actor, UUID agentId, UUID keyId) {
        Agent agent = changing(actor, agentId);
        VirtualKey key = agents.key(agentId, keyId).orElseThrow(AgentProblem.KEY_NOT_FOUND::failure);
        if (key.status() == KeyStatus.active) {
            agents.revokeKey(keyId);
            publish(actor.tenantId(), agent);
            events.publishEvent(ActionTaken.of(actor.tenantId(), "agent.key_revoked", "agent", agentId,
                    Map.of("keyId", keyId)));
        }
    }

    @Transactional(readOnly = true)
    List<Mandate> mandates(UUID tenantId) {
        session.bind(tenantId);
        return agents.mandates();
    }

    @Transactional(readOnly = true)
    Mandate getMandate(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return mandate(id);
    }

    private static void requireThreshold(MandateDetails details) {
        if (details.approvalThreshold().compareTo(details.maxPerOperation()) > 0) {
            throw AgentProblem.THRESHOLD_ABOVE_LIMIT.failure();
        }
    }

    @Transactional
    Mandate createMandate(UUID tenantId, MandateDetails details) {
        requireThreshold(details);
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            agents.insertMandate(tenantId, id, details, clock.instant());
        } catch (DuplicateKeyException e) {
            throw AgentProblem.MANDATE_NAME_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "mandate.created", "mandate", id, terms(details)));
        return mandate(id);
    }

    @Transactional
    Mandate updateMandate(UUID tenantId, UUID id, MandateDetails details) {
        requireThreshold(details);
        session.bind(tenantId);
        agents.mandateForUpdate(id).orElseThrow(AgentProblem.MANDATE_NOT_FOUND::failure);
        try {
            agents.updateMandate(id, details, clock.instant());
        } catch (DuplicateKeyException e) {
            throw AgentProblem.MANDATE_NAME_TAKEN.failure();
        }
        events.publishEvent(ActionTaken.of(tenantId, "mandate.updated", "mandate", id, terms(details)));
        return mandate(id);
    }

    /**
     * Publishing an already published mandate changes nothing. A new version also goes to the NEAR notary as a
     * hash of its terms (ADR 0009).
     */
    @Transactional
    Mandate publishMandate(UUID tenantId, UUID id) {
        session.bind(tenantId);
        Mandate current = agents.mandateForUpdate(id).orElseThrow(AgentProblem.MANDATE_NOT_FOUND::failure);
        if (current.status() == MandateStatus.published) {
            return current;
        }
        String expression = MandateExpression.of(current);
        agents.publishMandate(id, expression, current.approvalThreshold(), clock.instant());
        agents.agentsOfMandate(id).forEach(agent -> publish(tenantId, agent));
        int version = current.version() + 1;
        String hash = MandateHash.of(tenantId, id, version, current.approvalThreshold(), expression);
        agents.insertNotarization(tenantId, id, version, hash, clock.instant());
        events.publishEvent(new MandatePublished(tenantId, id, version, hash, clock.instant()));
        events.publishEvent(ActionTaken.of(tenantId, "mandate.published", "mandate", id,
                Map.of("version", version, "hash", hash)));
        return mandate(id);
    }

    @Transactional
    void deleteMandate(UUID tenantId, UUID id) {
        session.bind(tenantId);
        agents.mandateForUpdate(id).orElseThrow(AgentProblem.MANDATE_NOT_FOUND::failure);
        if (agents.mandateInUse(id)) {
            throw AgentProblem.MANDATE_IN_USE.failure();
        }
        agents.deleteMandate(id);
        events.publishEvent(ActionTaken.of(tenantId, "mandate.deleted", "mandate", id, Map.of()));
    }

    /** The money terms of a mandate, for the activity log. */
    private static Map<String, Object> terms(MandateDetails d) {
        return ActionTaken.details("maxPerOperation", d.maxPerOperation().toPlainString(), "approvalThreshold",
                d.approvalThreshold().toPlainString(), "models", String.join(",", d.models()), "vendors",
                String.join(",", d.vendors()), "hours", d.hours());
    }

    /** A mandate never published has no terms, so the gateway gets no expression and denies the agent. */
    private void publish(UUID tenantId, Agent agent) {
        Optional<PublishedTerms> terms = agents.publishedTerms(agent.mandateId());
        events.publishEvent(new AgentStateChanged(tenantId, agent.id(), agents.activeKeyHashes(agent.id()),
                agent.status() == AgentStatus.suspended,
                terms.map(PublishedTerms::version).orElse(null),
                terms.map(PublishedTerms::expression).orElse(null),
                terms.map(PublishedTerms::approvalThreshold).orElse(null),
                clock.instant()));
    }
}
