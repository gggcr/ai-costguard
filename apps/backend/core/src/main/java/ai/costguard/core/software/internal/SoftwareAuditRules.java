package ai.costguard.core.software.internal;

import ai.costguard.core.findings.AuditRules;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.Names;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * </ul>
 */
@Component
class SoftwareAuditRules implements AuditRules {

    static final String UNUSED = "license.unused";
    static final String DISMISSED = "license.dismissed";
    static final String IDLE_SEATS = "subscription.idle-seats";
    static final String DUPLICATE = "subscription.duplicate";
    static final String RENEWAL = "contract.renewal";

    private final SoftwareRepository software;
    private final EmployeeDirectory people;
    private final JdbcClient jdbc;
    private final int unusedDays;

    SoftwareAuditRules(SoftwareRepository software, EmployeeDirectory people, JdbcClient jdbc,
            @Value("${costguard.audit.unused-days:30}") int unusedDays) {
        this.software = software;
        this.people = people;
        this.jdbc = jdbc;
        this.unusedDays = unusedDays;
    }

    @Override
    public Set<String> rules() {
        return Set.of(UNUSED, DISMISSED, IDLE_SEATS, DUPLICATE, RENEWAL);
    }

    private static BigDecimal monthly(Subscription s) {
        return s.billingPeriod() == BillingPeriod.year
                ? s.pricePerSeat().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN)
                : s.pricePerSeat();
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
        return found;
    }
}
