package ai.costguard.core.plans.internal;

import ai.costguard.core.plans.Limit;
import ai.costguard.core.plans.PlanAccessEnded;
import ai.costguard.core.plans.PlanAccessEnding;
import ai.costguard.core.plans.PlanEnrollment;
import ai.costguard.core.plans.PlanLimits;
import ai.costguard.core.plans.TenantPlanChanged;
import ai.costguard.core.privacy.CompactedRecordsErased;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PlanService implements PlanLimits, PlanEnrollment {

    static final String TOPIC = "tenants.plans.v1";

    private final PlanRepository plans;
    private final PlansProperties properties;
    private final Payments payments;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    PlanService(PlanRepository plans, PlansProperties properties, Payments payments, TenantSession session,
            Clock clock, ApplicationEventPublisher events, TransactionTemplate transactions) {
        this.plans = plans;
        this.properties = properties;
        this.payments = payments;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.transactions = transactions;
    }

    private static Instant plus(Instant from, Period period) {
        return from.atOffset(ZoneOffset.UTC).plus(period).toInstant();
    }

    private PlansProperties.Plan spec(String plan) {
        return properties.plan(plan);
    }

    private PlanLimitsView limits(String plan) {
        PlansProperties.Limits l = spec(plan).limits();
        return new PlanLimitsView(l.agents(), l.employees(), l.connectedSystems(), l.operationsPerMonth());
    }

    private Long allowed(String plan, Limit limit) {
        PlansProperties.Limits l = spec(plan).limits();
        return switch (limit) {
            case AGENTS -> l.agents();
            case EMPLOYEES -> l.employees();
            case CONNECTED_SYSTEMS -> l.connectedSystems();
        };
    }

    private void publish(PlanState state, long version, Instant now) {
        events.publishEvent(new TenantPlanChanged(state.tenantId(), state.plan(), state.readOnly(now),
                spec(state.plan()).limits().operationsPerMonth(), state.accessUntil(), version, now));
    }

    // --- PlanEnrollment

    @Override
    public void startTrial(UUID tenantId) {
        Instant now = clock.instant();
        Instant end = plus(now, properties.trialLength());
        plans.insert(tenantId, PlanName.TRIAL, end, end, now);
        plans.schedule(tenantId, end);
        publish(new PlanState(tenantId, PlanName.TRIAL, end, end, 1), 1, now);
    }

    @Override
    public void forget(UUID tenantId) {
        plans.forget(tenantId);
        events.publishEvent(new CompactedRecordsErased(tenantId,
                List.of(new CompactedRecordsErased.Key(TOPIC, tenantId.toString()))));
    }

    // --- PlanLimits

    @Override
    public void requireRoom(UUID tenantId, Limit limit, LongSupplier used) {
        Optional<PlanState> found = plans.stateForUpdate();
        if (found.isEmpty()) {
            return;
        }
        PlanState state = found.get();
        if (state.readOnly(clock.instant())) {
            throw PlanProblem.PLAN_READ_ONLY.failure();
        }
        Long allowed = allowed(state.plan(), limit);
        if (allowed == null) {
            return;
        }
        long now = used.getAsLong();
        if (now >= allowed) {
            throw new PlanLimitExceeded(limit, state.plan(), spec(state.plan()).title(), allowed, now).failure();
        }
    }

    // --- The API

    List<PlanOffer> catalog() {
        return PlanName.ALL.stream().map(name -> {
            PlansProperties.Plan plan = spec(name);
            return new PlanOffer(name, plan.title(), plan.monthlyPrice(), plan.currency(), limits(name));
        }).toList();
    }

    private CurrentPlan view(PlanState state) {
        PlanStatus status = state.readOnly(clock.instant()) ? PlanStatus.read_only
                : PlanName.TRIAL.equals(state.plan()) ? PlanStatus.trial : PlanStatus.active;
        return new CurrentPlan(state.plan(), spec(state.plan()).title(), status, state.trialEndsAt(),
                state.accessUntil(), limits(state.plan()));
    }

    @Transactional(readOnly = true)
    Optional<CurrentPlan> current(UUID tenantId) {
        session.bind(tenantId);
        return plans.state().map(this::view);
    }

    /** For the read-only guard: false for a company without a plan. */
    @Transactional(readOnly = true)
    boolean readOnly(UUID tenantId) {
        session.bind(tenantId);
        return plans.state().map(s -> s.readOnly(clock.instant())).orElse(false);
    }

    /**
     * Creates the payment, asks the provider for it outside any transaction (a slow provider must not hold a
     * connection), then records what the provider said. A payment that succeeded at once is applied at once.
     */
    CheckoutView checkout(UUID tenantId, String plan) {
        if (!PlanName.PAID.contains(plan)) {
            throw PlanProblem.PLAN_UNKNOWN.failure();
        }
        PaymentProvider provider = payments.provider().orElseThrow(PlanProblem.PAYMENT_PROVIDER_MISSING::failure);
        PlansProperties.Plan spec = spec(plan);
        UUID paymentId = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            session.bind(tenantId);
            plans.insertPayment(paymentId, tenantId, plan, spec.monthlyPrice(), spec.currency(), provider.name(),
                    clock.instant());
        });
        PaymentProvider.Checkout checkout;
        try {
            checkout = provider.start(new PaymentProvider.PaymentRequest(paymentId, tenantId, plan,
                    spec.monthlyPrice(), spec.currency(), "CostGuard, " + spec.title(),
                    properties.payments().returnUrl()));
        } catch (RuntimeException e) {
            // Nothing was charged that the core knows of: the payment is closed, a new checkout starts afresh.
            transactions.executeWithoutResult(tx -> {
                session.bind(tenantId);
                plans.status(paymentId, "canceled", null);
            });
            throw PlanProblem.PAYMENT_PROVIDER_FAILED.failure();
        }
        return transactions.execute(tx -> {
            session.bind(tenantId);
            plans.started(paymentId, tenantId, provider.name(), checkout.providerPaymentId());
            settle(paymentId, checkout.status());
            String status = plans.paymentForUpdate(paymentId).orElseThrow().status();
            return new CheckoutView(paymentId, status,
                    checkout.status() == PaymentProvider.Status.PENDING ? checkout.confirmationUrl() : null,
                    plans.state().map(this::view).orElse(null));
        });
    }

    /** A notice the provider sent. Repeating it changes nothing. */
    void notice(String providerName, Map<String, String> headers, String body) {
        PaymentProvider provider = payments.byName(providerName)
                .orElseThrow(PlanProblem.PAYMENT_PROVIDER_UNKNOWN::failure);
        PaymentProvider.Notice notice = provider.notice(headers, body)
                .orElseThrow(PlanProblem.PAYMENT_NOTICE_INVALID::failure);
        PaymentRef ref = transactions.execute(tx -> plans.ref(provider.name(), notice.providerPaymentId()))
                .orElseThrow(PlanProblem.PAYMENT_UNKNOWN::failure);
        transactions.executeWithoutResult(tx -> {
            session.bind(ref.tenantId());
            settle(ref.paymentId(), notice.status());
        });
    }

    /** Inside a transaction bound to the tenant. Only a pending payment moves, so a repeated notice does nothing. */
    private void settle(UUID paymentId, PaymentProvider.Status status) {
        PaymentRow payment = plans.paymentForUpdate(paymentId).orElseThrow(PlanProblem.PAYMENT_UNKNOWN::failure);
        if (!"pending".equals(payment.status()) || status == PaymentProvider.Status.PENDING) {
            return;
        }
        Instant now = clock.instant();
        if (status == PaymentProvider.Status.CANCELED) {
            plans.status(paymentId, "canceled", null);
            return;
        }
        plans.status(paymentId, "succeeded", now);
        PlanState before = plans.stateForUpdate().orElse(null);
        // A period paid before the old one ends adds to it; after the end, it starts now.
        Instant from = before == null || before.accessUntil() == null || before.accessUntil().isBefore(now)
                || PlanName.TRIAL.equals(before.plan()) ? now : before.accessUntil();
        Instant until = plus(from, properties.paidPeriod());
        long version;
        if (before == null) {
            plans.insert(payment.tenantId(), payment.plan(), null, until, now);
            version = 1;
        } else {
            version = plans.update(payment.plan(), until, now);
        }
        plans.schedule(payment.tenantId(), until);
        PlanState after = new PlanState(payment.tenantId(), payment.plan(),
                before == null ? null : before.trialEndsAt(), until, version);
        publish(after, version, now);
        events.publishEvent(ActionTaken.of(payment.tenantId(), "plan.paid", "payment", paymentId,
                Map.of("plan", payment.plan(), "amount", payment.amount(), "currency", payment.currency(),
                        "until", until.toString())));
    }

    // --- The watcher

    List<UUID> due() {
        Instant now = clock.instant();
        return transactions.execute(tx -> plans.due(now, plus(now, properties.warnBefore()), 100));
    }

    /**
     * Warns the owners once before the period ends, and at the end makes the company read-only once: the gateway
     * hears it through tenants.plans.v1. Another instance holding the company's row handles it instead.
     */
    void watch(UUID tenantId) {
        transactions.executeWithoutResult(tx -> {
            Optional<ScheduleRow> row = plans.lockSchedule(tenantId);
            if (row.isEmpty() || row.get().accessUntil() == null) {
                return;
            }
            session.bind(tenantId);
            Optional<PlanState> found = plans.stateForUpdate();
            if (found.isEmpty()) {
                return;
            }
            PlanState state = found.get();
            Instant now = clock.instant();
            Instant until = row.get().accessUntil();
            boolean trial = PlanName.TRIAL.equals(state.plan());
            if (!now.isBefore(until)) {
                if (!until.equals(row.get().endedFor())) {
                    long version = plans.touch(now);
                    plans.ended(tenantId, until);
                    publish(state, version, now);
                    events.publishEvent(new PlanAccessEnded(tenantId, state.plan(), trial, until));
                }
            } else if (!until.equals(row.get().warnedFor())) {
                plans.warned(tenantId, until);
                events.publishEvent(new PlanAccessEnding(tenantId, state.plan(), trial, until));
            }
        });
    }
}
