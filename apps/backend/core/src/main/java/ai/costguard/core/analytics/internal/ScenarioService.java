package ai.costguard.core.analytics.internal;

import ai.costguard.core.software.SubscriptionCatalog;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What if" over the registry (ADR 0034). Costs are a month's worth: a yearly price counts as a twelfth, so a change
 * shows as the saving of every month it counts, not as a spike in the month of the renewal. Changes apply in order,
 * each on what the ones before left. With a published forecast the scenario runs over its months and takes the
 * difference off it; without one it runs over the next months.
 */
@Service
class ScenarioService {

    static final BigDecimal DEFAULT_DISCOUNT = new BigDecimal("0.17");
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final int DEFAULT_HORIZON = 12;

    private final SubscriptionCatalog catalog;
    private final ForecastRepository forecasts;
    private final TenantSession session;
    private final Clock clock;

    ScenarioService(SubscriptionCatalog catalog, ForecastRepository forecasts, TenantSession session, Clock clock) {
        this.catalog = catalog;
        this.forecasts = forecasts;
        this.session = session;
        this.clock = clock;
    }

    /** A subscription as a change leaves it. Price per billing period, like the registry. */
    private record State(int seats, BigDecimal price, boolean yearly, boolean cancelled) {

        BigDecimal monthly() {
            if (cancelled) {
                return BigDecimal.ZERO;
            }
            BigDecimal cost = price.multiply(BigDecimal.valueOf(seats));
            return yearly ? cost.divide(TWELVE, 2, RoundingMode.HALF_EVEN) : cost.setScale(2, RoundingMode.HALF_EVEN);
        }
    }

    @Transactional(readOnly = true)
    ScenarioResult run(UUID tenantId, ScenarioRequest request) {
        session.bind(tenantId);
        Optional<ForecastDocument> forecast = forecasts.newest().map(PublishedForecast::forecast);
        int horizon = request.horizon() != null ? request.horizon()
                : forecast.map(ForecastDocument::horizon).orElse(DEFAULT_HORIZON);
        Map<YearMonth, BigDecimal> forecastByMonth = new LinkedHashMap<>();
        forecast.ifPresent(f -> f.company().points().forEach(p -> forecastByMonth.put(YearMonth.parse(p.month()),
                p.value())));
        YearMonth first = forecastByMonth.isEmpty() ? YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(1)
                : forecastByMonth.keySet().iterator().next();
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            months.add(first.plusMonths(i));
        }

        Map<UUID, SubscriptionCatalog.Held> subs = new LinkedHashMap<>();
        catalog.subscriptions().forEach(s -> subs.put(s.id(), s));
        Map<UUID, TreeMap<YearMonth, State>> timeline = new HashMap<>();
        for (SubscriptionCatalog.Held s : subs.values()) {
            TreeMap<YearMonth, State> states = new TreeMap<>();
            states.put(YearMonth.of(1, 1), new State(s.seats(), s.pricePerSeat(), s.yearly(), false));
            timeline.put(s.id(), states);
        }

        List<ChangeEffect> effects = new ArrayList<>();
        for (ScenarioChange change : request.changes()) {
            List<SubscriptionCatalog.Held> targets;
            if (change.subscriptionId() != null) {
                SubscriptionCatalog.Held s = subs.get(change.subscriptionId());
                if (s == null) {
                    throw AnalyticsProblem.SCENARIO_SUBSCRIPTION_NOT_FOUND.failure();
                }
                targets = List.of(s);
            } else if (change.kind() == ChangeKind.freeSeats || change.kind() == ChangeKind.yearly) {
                targets = List.copyOf(subs.values());
            } else {
                throw AnalyticsProblem.CHANGE_NEEDS_SUBSCRIPTION.failure();
            }
            for (SubscriptionCatalog.Held s : targets) {
                apply(change, s, timeline.get(s.id()), first).ifPresent(effects::add);
            }
        }

        List<ScenarioMonth> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal last = BigDecimal.ZERO;
        for (YearMonth month : months) {
            BigDecimal before = BigDecimal.ZERO;
            BigDecimal after = BigDecimal.ZERO;
            for (TreeMap<YearMonth, State> states : timeline.values()) {
                before = before.add(states.firstEntry().getValue().monthly());
                after = after.add(states.floorEntry(month).getValue().monthly());
            }
            BigDecimal saved = before.subtract(after);
            BigDecimal predicted = forecastByMonth.get(month);
            rows.add(new ScenarioMonth(month.toString(), before, after, predicted,
                    predicted == null ? null : predicted.subtract(saved).max(BigDecimal.ZERO)));
            total = total.add(saved);
            last = saved;
        }
        return new ScenarioResult(rows, effects, last, total, !forecastByMonth.isEmpty());
    }

    /** The change on the subscription from its month; empty when it does not fit (a yearly one already). */
    private static Optional<ChangeEffect> apply(ScenarioChange change, SubscriptionCatalog.Held s,
            TreeMap<YearMonth, State> states, YearMonth first) {
        State now = states.lastEntry().getValue();
        YearMonth from = change.from() != null ? YearMonth.parse(change.from()) : first;
        State next;
        String what;
        switch (change.kind()) {
            case freeSeats -> {
                if (now.cancelled() || now.seats() <= s.assigned()) {
                    return Optional.empty();
                }
                next = new State(s.assigned(), now.price(), now.yearly(), false);
                what = "Оплачивать " + s.assigned() + " мест вместо " + now.seats();
            }
            case seats -> {
                if (change.seats() == null) {
                    throw AnalyticsProblem.CHANGE_NEEDS_VALUE.failure();
                }
                next = new State(change.seats(), now.price(), now.yearly(), now.cancelled());
                what = change.seats() + " мест вместо " + now.seats();
            }
            case cancel -> {
                next = new State(now.seats(), now.price(), now.yearly(), true);
                what = "Отказаться от подписки";
            }
            case yearly -> {
                if (now.yearly() || now.cancelled()) {
                    return Optional.empty();
                }
                BigDecimal discount = change.discount() != null ? change.discount() : DEFAULT_DISCOUNT;
                next = new State(now.seats(), now.price().multiply(TWELVE).multiply(BigDecimal.ONE.subtract(discount)),
                        true, false);
                if (change.from() == null) {
                    YearMonth renewal = YearMonth.from(s.renewalDate());
                    from = renewal.isBefore(first) ? first : renewal;
                }
                what = "Оплата за год со скидкой " + discount.movePointRight(2).stripTrailingZeros().toPlainString()
                        + "% с " + from;
            }
            case price -> {
                if (change.pricePerSeat() == null) {
                    throw AnalyticsProblem.CHANGE_NEEDS_VALUE.failure();
                }
                next = new State(now.seats(), change.pricePerSeat(), now.yearly(), now.cancelled());
                what = "Цена места " + change.pricePerSeat().toPlainString() + " вместо " + now.price().toPlainString();
            }
            default -> throw new IllegalStateException();
        }
        // a change counts from its month and stays: later changes of the same subscription build on it
        states.tailMap(from, true).clear();
        states.put(from, next);
        return Optional.of(new ChangeEffect(s.id(), s.vendor() + " " + s.product(), change.kind(), what,
                now.monthly().subtract(next.monthly())));
    }
}
