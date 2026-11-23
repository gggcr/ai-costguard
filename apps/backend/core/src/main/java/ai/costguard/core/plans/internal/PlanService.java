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
            return new PlanOffer(name, plan.title(), plan.monthlyPrice(), plan.currency(), limits(name),
                    plan.prices());
        }).toList();
    }

    private CurrentPlan view(PlanState state) {
        PlanStatus status = state.readOnly(clock.instant()) ? PlanStatus.read_only
                : PlanName.TRIAL.equals(state.plan()) ? PlanStatus.trial : PlanStatus.active;
        return new CurrentPlan(state.plan(), spec(state.plan()).title(), status, state.trialEndsAt(),
                state.accessUntil(), limits(state.plan()), plans.subscription()
                        .map(s -> new SubscriptionView(s.provider(), s.status(), s.cancelAtPeriodEnd(), s.paidUntil()))
                        .orElse(null));
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
    CheckoutView checkout(UUID tenantId, String plan, String currency) {
        if (!PlanName.PAID.contains(plan)) {
            throw PlanProblem.PLAN_UNKNOWN.failure();
        }
        PaymentProvider provider = payments.provider().orElseThrow(PlanProblem.PAYMENT_PROVIDER_MISSING::failure);
        PlansProperties.Plan spec = spec(plan);
        String code = currency == null || currency.isBlank() ? spec.currency()
                : currency.toUpperCase(java.util.Locale.ROOT);
        java.math.BigDecimal amount = spec.price(code).orElseThrow(PlanProblem.PLAN_CURRENCY_UNKNOWN::failure);
        UUID paymentId = UUID.randomUUID();
        // One subscription per company (ADR 0077): a change of plan or a cancellation goes through the portal.
        String customer = transactions.execute(tx -> {
            session.bind(tenantId);
            Optional<SubscriptionRow> subscription = plans.subscription();
            if (subscription.isPresent() && subscription.get().live()) {
                throw PlanProblem.SUBSCRIPTION_EXISTS.failure();
            }
            plans.insertPayment(paymentId, tenantId, plan, amount, code, provider.name(), clock.instant());
            return subscription.filter(s -> s.provider().equals(provider.name()))
                    .map(SubscriptionRow::customerId).orElse(null);
        });
        PaymentProvider.Checkout checkout;
        try {
            checkout = provider.start(new PaymentProvider.PaymentRequest(paymentId, tenantId, plan,
                    amount, code, "CostGuard, " + spec.title(), properties.payments().returnUrl(), customer));
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

    /**
     * A notice the provider sent. Repeating it changes nothing: a payment moves only from pending, and an event the
     * provider delivered before (its id is kept with the change, in the same transaction) is skipped. An event about
     * a subscription the core has not recorded yet fails, so the provider delivers it again later.
     */
    void notice(String providerName, Map<String, String> headers, String body) {
        PaymentProvider provider = payments.byName(providerName)
                .orElseThrow(PlanProblem.PAYMENT_PROVIDER_UNKNOWN::failure);
        PaymentProvider.Notice notice = provider.notice(headers, body)
                .orElseThrow(PlanProblem.PAYMENT_NOTICE_INVALID::failure);
        Instant now = clock.instant();
        if (notice.providerPaymentId() != null) {
            PaymentRef ref = transactions.execute(tx -> plans.ref(provider.name(), notice.providerPaymentId()))
                    .orElseThrow(PlanProblem.PAYMENT_UNKNOWN::failure);
            transactions.executeWithoutResult(tx -> {
                if (!plans.firstDelivery(provider.name(), notice.eventId(), ref.tenantId(), now)) {
                    return;
                }
                session.bind(ref.tenantId());
                settle(ref.paymentId(), notice.status());
                PaymentProvider.SubscriptionChange started = notice.subscription();
                if (started != null && notice.status() == PaymentProvider.Status.SUCCEEDED) {
                    PaymentRow payment = plans.paymentForUpdate(ref.paymentId()).orElseThrow();
                    Instant until = plans.state().map(PlanState::accessUntil).orElse(null);
                    plans.subscribe(new SubscriptionRow(ref.tenantId(), provider.name(), started.subscriptionId(),
                            started.customerId(), payment.plan(), "active", false, until,
                            notice.at() == null ? now : notice.at()), now);
                }
            });
        } else if (notice.subscription() != null) {
            PaymentProvider.SubscriptionChange change = notice.subscription();
            UUID tenantId = transactions.execute(tx -> plans.subscriptionTenant(provider.name(),
                    change.subscriptionId())).orElseThrow(PlanProblem.SUBSCRIPTION_UNKNOWN::failure);
            transactions.executeWithoutResult(tx -> {
                if (!plans.firstDelivery(provider.name(), notice.eventId(), tenantId, now)) {
                    return;
                }
                session.bind(tenantId);
                change(tenantId, provider.name(), change, notice.at() == null ? now : notice.at(), now);
            });
        } else {
            transactions.executeWithoutResult(tx -> plans.firstDelivery(provider.name(), notice.eventId(), null, now));
        }
    }

    /**
     * Inside a transaction bound to the tenant. Access only grows from a paid period (taking the later end, so a
     * repeated or late event cannot shorten it); a status older than the stored one is not applied.
     */
    private void change(UUID tenantId, String provider, PaymentProvider.SubscriptionChange change, Instant at,
            Instant now) {
        SubscriptionRow row = plans.subscriptionForUpdate().orElseThrow(PlanProblem.SUBSCRIPTION_UNKNOWN::failure);
        if (!row.subscriptionId().equals(change.subscriptionId())) {
            // an old subscription of the company, replaced by a newer one: its news changes nothing
            return;
        }
        PlanState state = plans.stateForUpdate().orElse(null);
        String plan = change.plan() != null && PlanName.PAID.contains(change.plan()) ? change.plan() : row.plan();
        boolean newer = !at.isBefore(row.statusAt());
        Instant paidUntil = row.paidUntil();
        Instant accessUntil = state == null ? null : state.accessUntil();
        String status = newer ? change.status() : row.status();
        boolean cancel = newer && change.kind() == PaymentProvider.SubscriptionChange.Kind.UPDATED
                ? change.cancelAtPeriodEnd() : row.cancelAtPeriodEnd();
        switch (change.kind()) {
            case PAID -> {
                paidUntil = later(paidUntil, change.periodEnd());
                accessUntil = later(accessUntil, change.periodEnd());
                if (!change.first() && change.amount() != null) {
                    plans.insertPaid(UUID.randomUUID(), tenantId, plan, change.amount(), change.currency(), provider,
                            change.reference(), now);
                    events.publishEvent(ActionTaken.of(tenantId, "plan.renewed", "plan", tenantId,
                            Map.of("plan", plan, "amount", change.amount(), "currency", change.currency(),
                                    "until", String.valueOf(accessUntil))));
                }
            }
            case PAYMENT_FAILED -> {
                // the provider retries the card; the company keeps working for the grace period after what it paid
                if (paidUntil != null) {
                    accessUntil = later(accessUntil, plus(paidUntil, properties.payments().grace()));
                }
                events.publishEvent(ActionTaken.of(tenantId, "plan.payment_failed", "plan", tenantId,
                        Map.of("plan", plan, "graceUntil", String.valueOf(accessUntil))));
            }
            case ENDED -> events.publishEvent(ActionTaken.of(tenantId, "plan.subscription_ended", "plan",
                    tenantId, Map.of("plan", plan, "until", String.valueOf(accessUntil))));
            case STARTED, UPDATED -> {
                // a change of plan in the portal takes effect at once; the period stays what was paid
            }
        }
        plans.subscribe(new SubscriptionRow(tenantId, provider, row.subscriptionId(),
                change.customerId() == null ? row.customerId() : change.customerId(), plan, status, cancel, paidUntil,
                newer ? at : row.statusAt()), now);
        if (state != null && (!plan.equals(state.plan()) || !java.util.Objects.equals(accessUntil,
                state.accessUntil()))) {
            long version = plans.update(plan, accessUntil, now);
            if (accessUntil != null) {
                plans.schedule(tenantId, accessUntil);
            }
            publish(new PlanState(tenantId, plan, state.trialEndsAt(), accessUntil, version), version, now);
        }
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /** The provider's page for the owner: change or cancel the subscription, the card, the invoices. */
    PortalView portal(UUID tenantId) {
        PaymentProvider provider = payments.provider().orElseThrow(PlanProblem.PAYMENT_PROVIDER_MISSING::failure);
        String customer = transactions.execute(tx -> {
            session.bind(tenantId);
            return plans.subscription().filter(s -> s.provider().equals(provider.name()))
                    .map(SubscriptionRow::customerId).orElse(null);
        });
        if (customer == null) {
            throw PlanProblem.SUBSCRIPTION_MISSING.failure();
        }
        try {
            return provider.portal(customer, properties.payments().returnUrl()).map(PortalView::new)
                    .orElseThrow(PlanProblem.SUBSCRIPTION_MISSING::failure);
        } catch (RuntimeException e) {
            if (e instanceof ai.costguard.platform.errors.ApiException) {
                throw e;
            }
            throw PlanProblem.PAYMENT_PROVIDER_FAILED.failure();
        }
    }

    @Transactional(readOnly = true)
    List<PaymentHistoryRow> payments(UUID tenantId) {
        session.bind(tenantId);
        return plans.history(100);
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
