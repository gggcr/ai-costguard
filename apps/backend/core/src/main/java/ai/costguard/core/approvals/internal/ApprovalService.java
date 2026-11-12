package ai.costguard.core.approvals.internal;

import ai.costguard.core.agents.AgentOwners;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.ApprovalEscalated;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.approvals.ApprovalOpened;
import ai.costguard.core.approvals.LargeOperationApproved;
import ai.costguard.core.approvals.OwnerApprovals;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.platform.tenant.TenantSession;
import ai.costguard.core.shared.activity.ActionTaken;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ApprovalService implements ApprovalRequests, OwnerApprovals {

    private final ApprovalRepository approvals;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final EmployeeDirectory directory;
    private final AgentOwners owners;
    private final Duration ttl;
    private final BigDecimal confirmAbove;

    ApprovalService(ApprovalRepository approvals, TenantSession session, Clock clock,
            ApplicationEventPublisher events, EmployeeDirectory directory, AgentOwners owners,
            @Value("${costguard.approvals.ttl:PT4H}") Duration ttl,
            @Value("${costguard.notary.confirm-above:100000}") BigDecimal confirmAbove) {
        this.approvals = approvals;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.directory = directory;
        this.owners = owners;
        this.ttl = ttl;
        this.confirmAbove = confirmAbove;
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
        events.publishEvent(new ApprovalOpened(tenantId, id, agentId, description, amount, reason, expiresAt));
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
        return close(decider.tenantId(), approval, status, decider.email(), decision.comment(), false);
    }

    @Override
    public List<Waiting> waiting(Collection<UUID> agentIds) {
        Instant now = clock.instant();
        return approvals.list(ApprovalStatus.pending).stream()
                .filter(a -> agentIds.contains(a.agentId()) && now.isBefore(a.deadline()))
                .map(a -> new Waiting(a.id(), a.agentId(), a.description(), a.amount(), a.reason(), a.createdAt(),
                        a.deadline()))
                .toList();
    }

    /** The owner's own word is notarized whatever the amount (ADR 0067). */
    @Override
    public Optional<Decided> decide(UUID tenantId, UUID approvalId, UUID ownerId, String by, boolean approve,
            String comment) {
        session.bind(tenantId);
        Optional<Approval> found = approvals.findForUpdate(approvalId)
                .filter(a -> owners.ownerOf(a.agentId()).filter(ownerId::equals).isPresent());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Approval approval = found.get();
        if (approval.status() != ApprovalStatus.pending || !clock.instant().isBefore(approval.deadline())) {
            throw new OwnerApprovals.AlreadyDecided();
        }
        Approval closed = close(tenantId, approval, approve ? ApprovalStatus.approved : ApprovalStatus.rejected, by,
                comment, true);
        return Optional.of(new Decided(closed.id(), closed.status().name(), closed.notaryHash()));
    }

    private void requireOwnAgent(Decider decider, UUID agentId) {
        Optional<UUID> employee = directory.employeeOfSubject(decider.subject());
        if (employee.isEmpty() || !employee.equals(owners.ownerOf(agentId))) {
            throw ApprovalProblem.NOT_AGENT_OWNER.failure();
        }
    }

    private Approval close(UUID tenantId, Approval approval, ApprovalStatus status, String by, String comment,
            boolean byOwner) {
        approvals.close(approval.id(), status, by, comment);
        approvals.removeTimer(approval.id());
        events.publishEvent(new ActionTaken(tenantId, "approval." + status.name(), "approval", approval.id(),
                ActionTaken.details("operationId", approval.operationId(), "agentId", approval.agentId(), "amount",
                        approval.amount().toPlainString()), by == null ? "approvals-timer" : null));
        events.publishEvent(new ApprovalDecided(tenantId, approval.id(), approval.operationId(), approval.agentId(),
                status.name(), approval.amount(), clock.instant()));
        if (status == ApprovalStatus.approved && (byOwner || approval.amount().compareTo(confirmAbove) >= 0)) {
            requestConfirmation(tenantId, approval, by);
        }
        return approvals.find(approval.id()).orElseThrow();
    }

    /** A large approved operation goes to the NEAR notary as hashes only (ADR 0009). */
    private void requestConfirmation(UUID tenantId, Approval approval, String by) {
        String operationHash = NotaryHashes.operation(tenantId, approval.operationId(), approval.agentId(),
                approval.amount(), approval.id());
        approvals.setNotaryHash(approval.id(), operationHash);
        events.publishEvent(new LargeOperationApproved(tenantId, approval.id(), approval.operationId(), operationHash,
                NotaryHashes.approver(tenantId, approval.id(), by), clock.instant()));
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
            close(due.tenantId(), approval.get(), ApprovalStatus.expired, null, null, false);
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
