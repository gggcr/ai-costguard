package ai.costguard.core.approvals.internal;

import ai.costguard.core.agents.AgentOwners;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ApprovalService implements ApprovalRequests {

    private final ApprovalRepository approvals;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final EmployeeDirectory directory;
    private final AgentOwners owners;
    private final Duration ttl;

    ApprovalService(ApprovalRepository approvals, TenantSession session, Clock clock,
            ApplicationEventPublisher events, EmployeeDirectory directory, AgentOwners owners,
            @Value("${costguard.approvals.ttl:PT4H}") Duration ttl) {
        this.approvals = approvals;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.directory = directory;
        this.owners = owners;
        this.ttl = ttl;
    }

    @Override
    @Transactional
    public UUID open(UUID tenantId, UUID operationId, UUID agentId, String description, BigDecimal amount,
            String reason) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        if (!approvals.insert(tenantId, id, operationId, agentId, description, amount, reason, now, expiresAt)) {
            return approvals.byOperation(operationId).orElseThrow();
        }
        approvals.scheduleTimer(id, tenantId, now.plus(ttl.dividedBy(2)), expiresAt);
        return id;
    }

    @Transactional(readOnly = true)
    List<Approval> list(UUID tenantId, ApprovalStatus status) {
        session.bind(tenantId);
        return approvals.list(status);
    }

    @Transactional(readOnly = true)
    Approval get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return approvals.find(id).orElseThrow(ApprovalProblem.APPROVAL_NOT_FOUND::failure);
    }

    /** The row lock settles a race with another person or with the timer: the second one sees it closed. */
    @Transactional
    Approval decide(Decider decider, UUID id, Decision decision) {
        session.bind(decider.tenantId());
        Approval approval = approvals.findForUpdate(id).orElseThrow(ApprovalProblem.APPROVAL_NOT_FOUND::failure);
        if (decider.restricted()) {
            requireOwnAgent(decider, approval.agentId());
        }
        if (approval.status() != ApprovalStatus.pending || !clock.instant().isBefore(approval.deadline())) {
            throw ApprovalProblem.ALREADY_DECIDED.failure();
        }
        ApprovalStatus status = decision.verdict() == Verdict.approve ? ApprovalStatus.approved
                : ApprovalStatus.rejected;
        return close(decider.tenantId(), approval, status, decider.email(), decision.comment());
    }

    private void requireOwnAgent(Decider decider, UUID agentId) {
        Optional<UUID> employee = directory.employeeOfUser(decider.email());
        if (employee.isEmpty() || !employee.equals(owners.ownerOf(agentId))) {
            throw ApprovalProblem.NOT_AGENT_OWNER.failure();
        }
    }

    private Approval close(UUID tenantId, Approval approval, ApprovalStatus status, String by, String comment) {
        approvals.close(approval.id(), status, by, comment);
        approvals.removeTimer(approval.id());
        events.publishEvent(new ApprovalDecided(tenantId, approval.id(), approval.operationId(), approval.agentId(),
                status.name(), approval.amount(), clock.instant()));
        return approvals.find(approval.id()).orElseThrow();
    }

    @Transactional(readOnly = true)
    List<DueApproval> dueForExpiry() {
        return approvals.dueForExpiry(clock.instant(), 500);
    }

    @Transactional(readOnly = true)
    List<DueApproval> dueForEscalation() {
        return approvals.dueForEscalation(clock.instant(), 500);
    }

    @Transactional
    void expire(DueApproval due) {
        session.bind(due.tenantId());
        Optional<Approval> approval = approvals.findForUpdate(due.approvalId());
        if (approval.filter(a -> a.status() == ApprovalStatus.pending).isPresent()) {
            close(due.tenantId(), approval.get(), ApprovalStatus.expired, null, null);
        } else {
            approvals.removeTimer(due.approvalId());
        }
    }

    @Transactional
    void escalate(DueApproval due) {
        session.bind(due.tenantId());
        approvals.clearEscalation(due.approvalId());
        approvals.findForUpdate(due.approvalId()).filter(a -> a.status() == ApprovalStatus.pending).ifPresent(a -> {
            approvals.markEscalated(a.id(), clock.instant());
            events.publishEvent(new ApprovalEscalated(due.tenantId(), a.id(), a.agentId(), a.amount(), a.deadline()));
        });
    }
}
