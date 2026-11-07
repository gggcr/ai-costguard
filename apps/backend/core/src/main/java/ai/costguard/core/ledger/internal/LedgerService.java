package ai.costguard.core.ledger.internal;

import ai.costguard.core.ledger.BudgetLimitChanged;
import ai.costguard.core.ledger.OperationCharged;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService implements OperationPosting {

    private final LedgerRepository ledger;
    private final TenantSession session;
    private final ApplicationEventPublisher events;

    LedgerService(LedgerRepository ledger, TenantSession session, ApplicationEventPublisher events) {
        this.ledger = ledger;
        this.session = session;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<Budget> budgets(UUID tenantId) {
        session.bind(tenantId);
        return ledger.budgets();
    }

    @Transactional(readOnly = true)
    Budget budget(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return found(id);
    }

    private Budget found(UUID id) {
        return ledger.budget(id).orElseThrow(LedgerProblem.BUDGET_NOT_FOUND::failure);
    }

    @Transactional
    Budget create(UUID tenantId, NewBudget request) {
        session.bind(tenantId);
        requireHierarchy(request);
        UUID id = UUID.randomUUID();
        try {
            ledger.insert(tenantId, id, request);
        } catch (DuplicateKeyException e) {
            throw LedgerProblem.BUDGET_EXISTS.failure();
        }
        Budget created = found(id);
        announce(tenantId, created);
        return created;
    }

    private void requireHierarchy(NewBudget request) {
        if (request.level() == BudgetLevel.company) {
            if (request.parentId() != null || request.refId() != null) {
                throw LedgerProblem.INVALID_HIERARCHY.failure();
            }
            return;
        }
        if (request.parentId() == null || request.refId() == null) {
            throw LedgerProblem.INVALID_HIERARCHY.failure();
        }
        Budget parent = ledger.budget(request.parentId()).orElseThrow(LedgerProblem.PARENT_NOT_FOUND::failure);
        BudgetLevel expected = request.level() == BudgetLevel.agent ? BudgetLevel.department : BudgetLevel.company;
        if (parent.level() != expected) {
            throw LedgerProblem.INVALID_HIERARCHY.failure();
        }
    }

    /** A limit below what is spent is allowed: the gateway then simply denies new operations. */
    @Transactional
    Budget setLimit(UUID tenantId, UUID id, BigDecimal limit) {
        session.bind(tenantId);
        if (!ledger.setLimit(id, limit)) {
            throw LedgerProblem.BUDGET_NOT_FOUND.failure();
        }
        Budget changed = found(id);
        announce(tenantId, changed);
        return changed;
    }

    private void announce(UUID tenantId, Budget b) {
        events.publishEvent(new BudgetLimitChanged(tenantId, b.id(), b.parentId(), b.level().name(), b.refId(), b.limit(),
                b.currency(), b.version()));
    }

    @Transactional(readOnly = true)
    LedgerPage entries(UUID tenantId, UUID budgetId, int page, int size) {
        session.bind(tenantId);
        found(budgetId);
        return new LedgerPage(ledger.entries(budgetId, page, size), page, size);
    }

    @Override
    @Transactional
    public boolean charge(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, Instant occurredAt) {
        session.bind(tenantId);
        Optional<Budget> level = ledger.budgetOfAgent(agentId);
        if (level.isEmpty()) {
            return false;
        }
        String currency = level.get().currency();
        boolean first = true;
        boolean fresh = false;
        while (level.isPresent()) {
            Budget budget = level.get();
            boolean appended = ledger.append(tenantId, budget.id(), operationId, EntryKind.charge, amount, occurredAt);
            fresh |= first && appended;
            first = false;
            level = budget.parentId() == null ? Optional.empty() : ledger.budget(budget.parentId());
        }
        if (fresh) {
            events.publishEvent(new OperationCharged(tenantId, operationId, agentId, amount, currency, occurredAt));
        }
        return true;
    }

    @Override
    @Transactional
    public void reverse(UUID tenantId, UUID operationId, Instant occurredAt) {
        session.bind(tenantId);
        for (LedgerEntry charge : ledger.charges(operationId)) {
            ledger.append(tenantId, charge.budgetId(), operationId, EntryKind.reversal, charge.amount().negate(),
                    occurredAt);
        }
    }
}
