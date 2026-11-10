package ai.costguard.core.software.internal;

import ai.costguard.core.findings.AuditRules;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.Names;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Software rules. Savings are per month: a yearly price counts as a twelfth.
 *
 * <ul>
 *   <li>{@code license.unused}: no activity for {@code costguard.audit.unused-days} (30) days. A license with no
 *       activity at all is not flagged: no connector reported on it, so nothing is known.
 *   <li>{@code license.dismissed}: the holder was dismissed and still holds the seat.
 *   <li>{@code subscription.idle-seats}: paid seats nobody holds.
 *   <li>{@code subscription.duplicate}: two subscriptions of one vendor to one product; the cheaper can go.
 *   <li>{@code contract.renewal}: an auto-renewing contract whose notice period starts within 14 days. The saving
 *       is not known, so it is zero; the finding is a deadline.
 *   <li>{@code subscription.overlap}: two tools of different vendors that do the same job ({@link ToolCategories})
 *       for the same people. The saving is the cheaper seat of everyone who holds both; with no licenses known in
 *       either, two such tools of one department save the cheaper subscription.
 *   <li>{@code subscription.oversized-plan}: a plan with a user limit ({@code product_plan.max_users}) while a
 *       cheaper plan of the product fits the people who hold licenses. The saving is the difference.
 *   <li>{@code subscription.monthly-billing}: a monthly subscription that is used ({@link #STEADY_SHARE} of its
 *       seats held, so a year of it is no gamble) while a year is cheaper: by the yearly price of its plan, else by
 *       {@code costguard.audit.annual-discount} (0.17; 0 turns the guess off). A plan that names no yearly price is
 *       not sold by the year, so it is not flagged.
 * </ul>
 * ADR 0032 has the reasons.
 */
@Component
class SoftwareAuditRules implements AuditRules {

    static final String UNUSED = "license.unused";
    static final String DISMISSED = "license.dismissed";
    static final String IDLE_SEATS = "subscription.idle-seats";
    static final String DUPLICATE = "subscription.duplicate";
    static final String RENEWAL = "contract.renewal";
    static final String OVERLAP = "subscription.overlap";
    static final String OVERSIZED_PLAN = "subscription.oversized-plan";
    static final String MONTHLY_BILLING = "subscription.monthly-billing";

    /** The share of seats held that makes a monthly subscription steady enough to pay by the year. */
    static final double STEADY_SHARE = 0.8;

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private final SoftwareRepository software;
    private final EmployeeDirectory people;
    private final JdbcClient jdbc;
    private final int unusedDays;
    private final BigDecimal annualDiscount;

    SoftwareAuditRules(SoftwareRepository software, EmployeeDirectory people, JdbcClient jdbc,
            @Value("${costguard.audit.unused-days:30}") int unusedDays,
            @Value("${costguard.audit.annual-discount:0.17}") BigDecimal annualDiscount) {
        this.software = software;
        this.people = people;
        this.jdbc = jdbc;
        this.unusedDays = unusedDays;
        this.annualDiscount = annualDiscount;
    }

    @Override
    public Set<String> rules() {
        return Set.of(UNUSED, DISMISSED, IDLE_SEATS, DUPLICATE, RENEWAL, OVERLAP, OVERSIZED_PLAN, MONTHLY_BILLING);
    }

    private static BigDecimal monthly(Subscription s) {
        return perMonth(s.pricePerSeat(), s.billingPeriod());
    }

    private static BigDecimal perMonth(BigDecimal price, BillingPeriod period) {
        return period == BillingPeriod.year ? price.divide(TWELVE, 2, RoundingMode.HALF_EVEN) : price;
    }

    /** What the subscription costs in its billing period. */
    private static BigDecimal cost(Subscription s) {
        return s.pricePerSeat().multiply(BigDecimal.valueOf(s.seats()));
    }

    @Override
    public List<Detection> detect(UUID tenantId, LocalDate today) {
        List<Detection> found = new ArrayList<>();
        Map<UUID, Vendor> vendors = new HashMap<>();
        software.vendors().forEach(v -> vendors.put(v.id(), v));
        Map<UUID, LocalDate> dismissed = people.dismissed();
        List<Subscription> subscriptions = software.subscriptions();

        for (Subscription s : subscriptions) {
            String name = vendors.get(s.vendorId()).name() + " " + s.product();
            BigDecimal price = monthly(s);
            for (License l : software.licenses(s.id())) {
                if (dismissed.containsKey(l.employeeId())) {
                    found.add(new Detection(DISMISSED, DISMISSED + ":" + l.id(), "Лицензия уволенного: " + name,
                            "Сотрудник уволен " + dismissed.get(l.employeeId()) + ", а место " + name
                                    + " всё ещё за ним. Отзовите лицензию.", price, s.id()));
                } else if (l.lastActive() != null && l.lastActive().isBefore(today.minusDays(unusedDays))) {
                    found.add(new Detection(UNUSED, UNUSED + ":" + l.id(), "Лицензия не используется: " + name,
                            "Последняя активность " + l.lastActive() + ", больше " + unusedDays
                                    + " дней назад. Отзовите лицензию или уменьшите число мест.", price, s.id()));
                }
            }
            int idle = s.seats() - s.assigned();
            if (idle > 0) {
                found.add(new Detection(IDLE_SEATS, IDLE_SEATS + ":" + s.id(), "Свободные места: " + name,
                        "Оплачено " + s.seats() + " мест, занято " + s.assigned() + ". Уберите " + idle
                                + " при продлении.", price.multiply(BigDecimal.valueOf(idle)), s.id()));
            }
        }

        Map<String, List<Subscription>> byProduct = new HashMap<>();
        for (Subscription s : subscriptions) {
            byProduct.computeIfAbsent(s.vendorId() + "|" + Names.clean(s.product()), k -> new ArrayList<>()).add(s);
        }
        for (List<Subscription> same : byProduct.values()) {
            if (same.size() < 2) {
                continue;
            }
            Subscription cheaper = same.stream()
                    .min((a, b) -> monthly(a).multiply(BigDecimal.valueOf(a.seats()))
                            .compareTo(monthly(b).multiply(BigDecimal.valueOf(b.seats()))))
                    .orElseThrow();
            String name = vendors.get(cheaper.vendorId()).name() + " " + cheaper.product();
            found.add(new Detection(DUPLICATE, DUPLICATE + ":" + cheaper.id(), "Две подписки на " + name,
                    "У компании " + same.size() + " подписки на " + name + ". Перенесите пользователей в одну.",
                    monthly(cheaper).multiply(BigDecimal.valueOf(cheaper.seats())), cheaper.id()));
        }

        record Due(UUID id, UUID vendorId, String number, LocalDate lastDay) {
        }
        jdbc.sql("""
                select id, vendor_id, number, end_date - notice_days as last_day from contract
                where auto_renew and end_date >= ? and end_date - notice_days <= ?
                """).params(today, today.plusDays(14))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class),
                        rs.getString("number"), rs.getObject("last_day", LocalDate.class)))
                .list()
                .forEach(c -> found.add(new Detection(RENEWAL, RENEWAL + ":" + c.id() + ":" + c.lastDay(),
                        "Договор продлится сам: " + vendors.get(c.vendorId()).name() + " № " + c.number(),
                        "Отказаться от продления можно до " + c.lastDay() + ". Решите, нужен ли договор.",
                        BigDecimal.ZERO, null)));

        overlaps(subscriptions, vendors, found);
        List<Plan> plans = software.plans();
        for (Subscription s : subscriptions) {
            String name = vendors.get(s.vendorId()).name() + " " + s.product();
            Plan plan = planOf(s, plans).orElse(null);
            oversized(s, name, plan, plans, found);
            monthlyBilling(s, name, plan, found);
        }
        return found;
    }

    private void overlaps(List<Subscription> subscriptions, Map<UUID, Vendor> vendors, List<Detection> found) {
        Map<UUID, Set<UUID>> holders = new HashMap<>();
        software.holdings().forEach(h -> holders.computeIfAbsent(h.subscriptionId(), k -> new HashSet<>())
                .add(h.employeeId()));
        Map<UUID, Set<String>> jobs = new HashMap<>();
        for (Subscription s : subscriptions) {
            jobs.put(s.id(), ToolCategories.of(vendors.get(s.vendorId()).name(), s.product()));
        }
        for (int i = 0; i < subscriptions.size(); i++) {
            for (int j = i + 1; j < subscriptions.size(); j++) {
                Subscription a = subscriptions.get(i);
                Subscription b = subscriptions.get(j);
                // one vendor sells add-ons that look alike (Microsoft 365 and Teams Premium); one product twice is
                // subscription.duplicate
                if (a.vendorId().equals(b.vendorId())) {
                    continue;
                }
                Set<String> common = new TreeSet<>(jobs.get(a.id()));
                common.retainAll(jobs.get(b.id()));
                if (common.isEmpty()) {
                    continue;
                }
                Set<UUID> both = new HashSet<>(holders.getOrDefault(a.id(), Set.of()));
                both.retainAll(holders.getOrDefault(b.id(), Set.of()));
                BigDecimal saving;
                if (!both.isEmpty()) {
                    saving = monthly(a).min(monthly(b)).multiply(BigDecimal.valueOf(both.size()));
                } else if (a.assigned() == 0 && b.assigned() == 0 && a.departmentId().equals(b.departmentId())) {
                    saving = perMonth(cost(a), a.billingPeriod()).min(perMonth(cost(b), b.billingPeriod()));
                } else {
                    continue;
                }
                // the one fewer people hold is the one to drop; with equal numbers the cheaper seat
                Subscription drop = a.assigned() != b.assigned() ? (a.assigned() < b.assigned() ? a : b)
                        : monthly(a).compareTo(monthly(b)) <= 0 ? a : b;
                Subscription keep = drop == a ? b : a;
                String dropName = vendors.get(drop.vendorId()).name() + " " + drop.product();
                String keepName = vendors.get(keep.vendorId()).name() + " " + keep.product();
                String pair = a.id().compareTo(b.id()) < 0 ? a.id() + ":" + b.id() : b.id() + ":" + a.id();
                String who = both.isEmpty() ? "Оба оплачены для одного отдела"
                        : "Места в обоих у " + both.size() + " сотрудников";
                found.add(new Detection(OVERLAP, OVERLAP + ":" + pair,
                        "Пересекающиеся инструменты: " + keepName + " и " + dropName,
                        keepName + " и " + dropName + " решают одну задачу (" + String.join(", ", common)
                                + "). " + who + ". Оставьте один инструмент.", saving, drop.id()));
            }
        }
    }

    private static Optional<Plan> planOf(Subscription s, List<Plan> plans) {
        if (s.plan() == null) {
            return Optional.empty();
        }
        return plans.stream().filter(p -> sameProduct(p, s) && Names.clean(p.name()).equals(Names.clean(s.plan())))
                .findFirst();
    }

    private static boolean sameProduct(Plan p, Subscription s) {
        return p.vendorId().equals(s.vendorId()) && Names.clean(p.product()).equals(Names.clean(s.product()));
    }

    /** The price of the plan for this many users in this period; null when it is not sold so or is too small. */
    private static BigDecimal price(Plan p, BillingPeriod period, int users) {
        BigDecimal price = period == BillingPeriod.year ? p.priceYear() : p.priceMonth();
        if (price == null || p.maxUsers() != null && p.maxUsers() < users) {
            return null;
        }
        return p.perSeat() ? price.multiply(BigDecimal.valueOf(users)) : price;
    }

    private static void oversized(Subscription s, String name, Plan plan, List<Plan> plans, List<Detection> found) {
        // a plan with no user limit is priced per seat, and seats nobody holds are subscription.idle-seats
        if (plan == null || plan.maxUsers() == null || s.assigned() == 0) {
            return;
        }
        BigDecimal paid = cost(s);
        Plan cheapest = null;
        BigDecimal best = paid;
        for (Plan other : plans) {
            if (other.id().equals(plan.id()) || !sameProduct(other, s)) {
                continue;
            }
            BigDecimal price = price(other, s.billingPeriod(), s.assigned());
            if (price != null && price.compareTo(best) < 0) {
                best = price;
                cheapest = other;
            }
        }
        if (cheapest == null) {
            return;
        }
        found.add(new Detection(OVERSIZED_PLAN, OVERSIZED_PLAN + ":" + s.id(), "Избыточный тариф: " + name,
                "Тариф «" + plan.name() + "» рассчитан на " + plan.maxUsers() + " пользователей, а лицензии у "
                        + s.assigned() + ". Тариф «" + cheapest.name() + "» дешевле; проверьте, хватит ли его функций.",
                perMonth(paid.subtract(best), s.billingPeriod()), s.id()));
    }

    private void monthlyBilling(Subscription s, String name, Plan plan, List<Detection> found) {
        if (s.billingPeriod() != BillingPeriod.month || s.seats() == 0 || s.assigned() < STEADY_SHARE * s.seats()) {
            return;
        }
        BigDecimal saving;
        String why;
        if (plan != null) {
            if (plan.priceYear() == null || plan.priceMonth() == null) {
                return;
            }
            BigDecimal month = plan.priceYear().divide(TWELVE, 2, RoundingMode.HALF_EVEN);
            BigDecimal less = plan.priceMonth().subtract(month);
            saving = plan.perSeat() ? less.multiply(BigDecimal.valueOf(s.seats())) : less;
            why = "По тарифу «" + plan.name() + "» год стоит " + plan.priceYear().toPlainString() + ", это "
                    + month.toPlainString() + " в месяц вместо " + plan.priceMonth().toPlainString() + ".";
        } else {
            if (annualDiscount.signum() <= 0) {
                return;
            }
            saving = cost(s).multiply(annualDiscount).setScale(2, RoundingMode.HALF_EVEN);
            why = "Годовая оплата обычно дешевле на "
                    + annualDiscount.movePointRight(2).stripTrailingZeros().toPlainString()
                    + "%; уточните цену у поставщика.";
        }
        if (saving.signum() <= 0) {
            return;
        }
        found.add(new Detection(MONTHLY_BILLING, MONTHLY_BILLING + ":" + s.id(),
                "Помесячная оплата вместо годовой: " + name,
                "Занято " + s.assigned() + " из " + s.seats() + " мест, подписка используется. " + why,
                saving, s.id()));
    }
}
