package ai.costguard.core.assets.internal;

import ai.costguard.core.findings.AuditRules;
import ai.costguard.core.org.EmployeeDirectory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Equipment rules, the ones board gap 1 had no data for. Their savings are one-off, the cost of the asset, unlike
 * the monthly savings of software; and they do not show in invoices (board gap 3).
 *
 * <ul>
 *   <li>{@code asset.not-returned}: the holder was dismissed, or the return date passed.
 *   <li>{@code asset.idle}: back in stock for {@code costguard.audit.idle-days} (90) days; hand it out before
 *       buying a new one.
 *   <li>{@code asset.duplicate}: an employee holds two or more laptops, or two or more phones. Returning all but
 *       the dearest one saves the cost of the rest.
 * </ul>
 */
@Component
class AssetAuditRules implements AuditRules {

    static final String NOT_RETURNED = "asset.not-returned";
    static final String IDLE = "asset.idle";
    static final String DUPLICATE = "asset.duplicate";

    private final JdbcClient jdbc;
    private final EmployeeDirectory people;
    private final int idleDays;

    AssetAuditRules(JdbcClient jdbc, EmployeeDirectory people, @Value("${costguard.audit.idle-days:90}") int idleDays) {
        this.jdbc = jdbc;
        this.people = people;
        this.idleDays = idleDays;
    }

    @Override
    public Set<String> rules() {
        return Set.of(NOT_RETURNED, IDLE, DUPLICATE);
    }

    record Held(UUID assignmentId, String inventory, String model, BigDecimal cost, UUID employeeId,
            LocalDate returnDue, String type) {
    }

    record Stocked(UUID assetId, String inventory, String model, BigDecimal cost, LocalDate since) {
    }

    @Override
    public List<Detection> detect(UUID tenantId, LocalDate today) {
        List<Detection> found = new ArrayList<>();
        Map<UUID, LocalDate> dismissed = people.dismissed();
        List<Held> held = jdbc.sql("""
                select g.id, a.inventory_number, a.model, a.cost, g.employee_id, g.return_due, a.type
                from asset_assignment g join asset a on a.id = g.asset_id
                where g.returned_at is null and a.status = 'ASSIGNED'
                order by g.employee_id, a.inventory_number
                """).query((rs, n) -> new Held(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getObject(5, UUID.class), rs.getObject(6, LocalDate.class), rs.getString(7)))
                .list();
        held.forEach(h -> {
            String what = h.model() + " (инв. № " + h.inventory() + ")";
            LocalDate left = dismissed.get(h.employeeId());
            if (left != null) {
                found.add(new Detection(NOT_RETURNED, NOT_RETURNED + ":" + h.assignmentId(),
                        "Уволенный не сдал технику: " + what,
                        "Сотрудник уволен " + left + ", а " + what + " числится за ним. Верните технику.",
                        h.cost(), null));
            } else if (h.returnDue() != null && h.returnDue().isBefore(today)) {
                found.add(new Detection(NOT_RETURNED, NOT_RETURNED + ":" + h.assignmentId(),
                        "Техника не возвращена в срок: " + what,
                        "Срок возврата " + h.returnDue() + " прошёл. Верните технику или продлите выдачу.",
                        h.cost(), null));
            }
        });
        jdbc.sql("""
                select a.id, a.inventory_number, a.model, a.cost, max(g.returned_at) as since
                from asset a join asset_assignment g on g.asset_id = a.id
                where a.status = 'IN_STOCK'
                group by a.id, a.inventory_number, a.model, a.cost
                having max(g.returned_at) < ?
                """).param(today.minusDays(idleDays))
                .query((rs, n) -> new Stocked(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getObject(5, LocalDate.class)))
                .list().forEach(s -> found.add(new Detection(IDLE, IDLE + ":" + s.assetId(),
                        "Техника простаивает: " + s.model() + " (инв. № " + s.inventory() + ")",
                        "На складе с " + s.since() + ". Выдайте её вместо покупки новой.", s.cost(), null)));
        duplicates(held, dismissed.keySet(), found);
        return found;
    }

    /** Genitive after 2–4 and after 5 and more: «2 ноутбука», «5 ноутбуков». */
    private static final Map<String, String[]> DUPLICATE_NAMES = Map.of(
            "LAPTOP", new String[] {"ноутбука", "ноутбуков"}, "PHONE", new String[] {"телефона", "телефонов"});

    private static String counted(int n, String[] forms) {
        boolean few = n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14);
        return n + " " + (few ? forms[0] : forms[1]);
    }

    /** A dismissed employee is already found by {@code asset.not-returned}, so is not counted twice. */
    private static void duplicates(List<Held> held, Set<UUID> dismissed, List<Detection> found) {
        Map<UUID, Map<String, List<Held>>> byPerson = new TreeMap<>();
        held.stream().filter(h -> DUPLICATE_NAMES.containsKey(h.type()) && !dismissed.contains(h.employeeId()))
                .forEach(h -> byPerson.computeIfAbsent(h.employeeId(), k -> new TreeMap<>())
                        .computeIfAbsent(h.type(), k -> new ArrayList<>()).add(h));
        byPerson.forEach((employee, kinds) -> kinds.forEach((type, items) -> {
            if (items.size() < 2) {
                return;
            }
            BigDecimal total = items.stream().map(Held::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal dearest = items.stream().map(Held::cost).max(Comparator.naturalOrder()).orElseThrow();
            String list = items.stream().map(h -> h.model() + " (инв. № " + h.inventory() + ")")
                    .collect(Collectors.joining(", "));
            found.add(new Detection(DUPLICATE, DUPLICATE + ":" + employee + ":" + type,
                    "У сотрудника " + counted(items.size(), DUPLICATE_NAMES.get(type)) + ": " + list,
                    "За одним сотрудником числится " + list + ". Верните лишнее на склад и выдайте тем, кто ждёт "
                            + "новую технику.",
                    total.subtract(dearest), null));
        }));
    }
}
