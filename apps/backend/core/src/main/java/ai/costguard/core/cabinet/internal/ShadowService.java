package ai.costguard.core.cabinet.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.names.KnownServices;
import ai.costguard.core.shared.names.Language;
import ai.costguard.core.shared.names.Names;
import ai.costguard.core.shared.names.WrittenValues;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Теневые траты (ADR 0057): an employee legally reports software they paid for with their own card; management sees
 * only what several people pay for, never who. Rows keep the department and a pseudonym, never the employee.
 */
@Service
class ShadowService {

    private static final Logger log = LoggerFactory.getLogger(ShadowService.class);
    static final int MIN_PEOPLE = 3;

    /** Words of a service name -> its category, after the AI providers KnownServices knows. */
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        for (String w : List.of("figma", "canva", "adobe", "photoshop", "illustrator", "sketch", "pixso", "miro",
                "midjourney")) {
            CATEGORIES.put(w, "Дизайн");
        }
        for (String w : List.of("github", "gitlab", "jetbrains", "intellij", "pycharm", "docker", "vercel", "heroku",
                "postman", "cursor", "copilot", "sentry")) {
            CATEGORIES.put(w, "Разработка");
        }
        for (String w : List.of("zoom", "slack", "telegram", "loom", "calendly", "trueconf", "пачка", "pachca")) {
            CATEGORIES.put(w, "Общение и встречи");
        }
        for (String w : List.of("notion", "evernote", "obsidian", "yonote", "grammarly", "deepl", "office", "word",
                "excel", "google workspace", "microsoft 365")) {
            CATEGORIES.put(w, "Документы и заметки");
        }
        for (String w : List.of("dropbox", "google drive", "яндекс диск", "icloud", "onedrive", "box")) {
            CATEGORIES.put(w, "Хранение файлов");
        }
        for (String w : List.of("vpn", "1password", "lastpass", "bitwarden", "nordvpn")) {
            CATEGORIES.put(w, "Безопасность и доступ");
        }
        for (String w : List.of("trello", "asana", "jira", "monday", "clickup", "kaiten", "weeek", "linear")) {
            CATEGORIES.put(w, "Задачи и проекты");
        }
    }

    private final JdbcClient jdbc;
    private final EmployeeDirectory employees;
    private final Whoami whoami;
    private final TenantSession session;
    private final Clock clock;
    private final byte[] key;

    ShadowService(JdbcClient jdbc, EmployeeDirectory employees, Whoami whoami, TenantSession session, Clock clock,
            @Value("${costguard.cabinet.pseudonym-key:${CONNECTIONS_ENCRYPTION_KEY:}}") String key) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.whoami = whoami;
        this.session = session;
        this.clock = clock;
        if (key == null || key.isBlank()) {
            log.warn("No costguard.cabinet.pseudonym-key: shadow spend pseudonyms use a development key");
            key = "costguard-development-pseudonym-key";
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /** The pseudonym of an employee: the same for them every time, nothing that leads back without the key. */
    String pseudonym(UUID tenantId, UUID employeeId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((tenantId + "/" + employeeId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored categories (Russian, as V40 keeps them) in English, for an English company (ADR 0074). */
    private static final Map<String, String> CATEGORIES_EN = Map.ofEntries(Map.entry("Дизайн", "Design"),
            Map.entry("Разработка", "Development"), Map.entry("Общение и встречи", "Communication and meetings"),
            Map.entry("Документы и заметки", "Documents and notes"), Map.entry("Хранение файлов", "File storage"),
            Map.entry("Безопасность и доступ", "Security and access"), Map.entry("Задачи и проекты", "Tasks and projects"),
            Map.entry("ИИ", "AI"), Map.entry("Сервисы по подписке", "Subscription services"), Map.entry("Другое", "Other"));

    /** A stored category as the company reads it; one without a translation stays as it is. */
    static String categoryIn(Language language, String stored) {
        return language == Language.EN ? CATEGORIES_EN.getOrDefault(stored, stored) : stored;
    }

    /** The company's language; the tenant is bound. */
    private Language language() {
        return employees.company().map(EmployeeDirectory.CompanyCard::language).orElse(Language.RU);
    }

    static String category(String service) {
        if (KnownServices.aiProvider(service).isPresent()) {
            return "ИИ";
        }
        String s = Names.clean(service);
        for (var e : CATEGORIES.entrySet()) {
            if (s.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return KnownServices.saas(service) ? "Сервисы по подписке" : "Другое";
    }

    @Transactional
    ShadowSpend report(UUID tenantId, Jwt jwt, NewShadowSpend r) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        UUID id = store(tenantId, me, r, "form");
        return mine(tenantId, me).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    /** A sheet of several purchases: сервис, сумма, дата, назначение (the first row names the columns). */
    @Transactional
    ShadowImport load(UUID tenantId, Jwt jwt, String fileName, byte[] content) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        List<List<String>> table = PayrollFile.table(fileName, content);
        if (table.isEmpty()) {
            return new ShadowImport(0, List.of());
        }
        Map<String, Integer> at = new LinkedHashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).strip().toLowerCase(Locale.ROOT);
            if (h.startsWith("сервис") || h.startsWith("программ") || h.equals("service")) {
                at.putIfAbsent("service", i);
            } else if (h.startsWith("сумм") || h.equals("amount")) {
                at.putIfAbsent("amount", i);
            } else if (h.startsWith("дат") || h.equals("date")) {
                at.putIfAbsent("date", i);
            } else if (h.startsWith("назнач") || h.startsWith("зачем") || h.equals("purpose")) {
                at.putIfAbsent("purpose", i);
            }
        }
        if (!at.containsKey("service") || !at.containsKey("amount") || !at.containsKey("date")) {
            throw CabinetProblem.SHADOW_COLUMNS.failure();
        }
        List<PayrollImport.Skipped> skipped = new ArrayList<>();
        int stored = 0;
        var company = PayrollFile.style(fileName, employees.company().map(EmployeeDirectory.CompanyCard::country)
                .orElse(null));
        List<List<String>> body = table.subList(1, table.size());
        var amounts = WrittenValues.amounts(body.stream().map(row -> cell(row, at.get("amount"))).toList(), company);
        var days = WrittenValues.dates(body.stream().map(row -> cell(row, at.get("date"))).toList(), company);
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            String service = cell(row, at.get("service"));
            BigDecimal amount = PayrollFile.amount(cell(row, at.get("amount")), amounts);
            LocalDate date = PayrollFile.date(cell(row, at.get("date")), days);
            if (service.isBlank()) {
                skipped.add(new PayrollImport.Skipped(r + 1, "no service"));
            } else if (amount == null || amount.signum() < 0) {
                skipped.add(new PayrollImport.Skipped(r + 1, "bad amount"));
            } else if (date == null) {
                skipped.add(new PayrollImport.Skipped(r + 1, "bad date"));
            } else {
                store(tenantId, me, new NewShadowSpend(service, amount, null, date,
                        at.containsKey("purpose") ? cell(row, at.get("purpose")) : null), "file");
                stored++;
            }
        }
        return new ShadowImport(stored, skipped);
    }

    private static String cell(List<String> row, Integer i) {
        return i == null || i >= row.size() ? "" : row.get(i).strip();
    }

    private UUID store(UUID tenantId, UUID me, NewShadowSpend r, String source) {
        String department = employees.card(me).map(EmployeeDirectory.EmployeeCard::department).orElse("—");
        String currency = r.currency() != null ? r.currency()
                : employees.company().map(EmployeeDirectory.CompanyCard::currency).orElse("RUB");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into shadow_spend (id, tenant_id, reporter, department, service, service_key, category, amount,
                                          currency, paid_on, purpose, source, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, tenantId, pseudonym(tenantId, me), department, r.service().strip(),
                        Names.clean(r.service()), category(r.service()), r.amount().setScale(2, RoundingMode.HALF_UP),
                        currency, r.paidOn(), r.purpose() == null || r.purpose().isBlank() ? null : r.purpose().strip(),
                        source, Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    @Transactional(readOnly = true)
    List<ShadowSpend> mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        return mine(tenantId, whoami.employee(jwt));
    }

    private List<ShadowSpend> mine(UUID tenantId, UUID me) {
        Language language = language();
        return jdbc.sql("""
                select id, service, category, amount, currency, paid_on, purpose, created_at from shadow_spend
                where reporter = ? order by paid_on desc, created_at desc""").param(pseudonym(tenantId, me))
                .query((rs, i) -> new ShadowSpend(rs.getObject("id", UUID.class), rs.getString("service"),
                        categoryIn(language, rs.getString("category")), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getObject("paid_on", LocalDate.class), rs.getString("purpose"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    void delete(UUID tenantId, Jwt jwt, UUID id) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        int n = jdbc.sql("delete from shadow_spend where id = ? and reporter = ?").params(id, pseudonym(tenantId, me))
                .update();
        if (n == 0) {
            throw CabinetProblem.SHADOW_NOT_FOUND.failure();
        }
    }

    /** Aggregates only, and only where at least {@link #MIN_PEOPLE} different people reported (k-anonymity). */
    @Transactional(readOnly = true)
    ShadowSummary summary(UUID tenantId) {
        session.bind(tenantId);
        Language language = language();
        record Row(String key, String service, String category, String department, String reporter,
                BigDecimal amount, String currency, LocalDate paidOn) {
        }
        List<Row> rows = jdbc.sql("""
                select service_key, service, category, department, reporter, amount, currency, paid_on
                from shadow_spend where paid_on >= ?""").param(LocalDate.now(clock).minusMonths(12))
                .query((rs, i) -> new Row(rs.getString("service_key"), rs.getString("service"),
                        rs.getString("category"), rs.getString("department"), rs.getString("reporter"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getObject("paid_on", LocalDate.class)))
                .list();
        Map<String, List<Row>> byService = new LinkedHashMap<>();
        rows.forEach(r -> byService.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r));
        List<ShadowSummary.Service> shown = new ArrayList<>();
        int hidden = 0;
        for (List<Row> group : byService.values()) {
            long people = group.stream().map(Row::reporter).distinct().count();
            if (people < MIN_PEOPLE) {
                hidden++;
                continue;
            }
            Map<String, List<Row>> byDepartment = new LinkedHashMap<>();
            group.forEach(r -> byDepartment.computeIfAbsent(r.department(), k -> new ArrayList<>()).add(r));
            List<ShadowSummary.Department> departments = new ArrayList<>();
            BigDecimal others = BigDecimal.ZERO;
            int othersPeople = 0;
            for (var d : byDepartment.entrySet()) {
                int p = (int) d.getValue().stream().map(Row::reporter).distinct().count();
                BigDecimal total = d.getValue().stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (p >= MIN_PEOPLE) {
                    departments.add(new ShadowSummary.Department(d.getKey(), p, total));
                } else {
                    others = others.add(total);
                    othersPeople += p;
                }
            }
            if (othersPeople > 0) {
                departments.add(new ShadowSummary.Department(language.pick("Другие отделы", "Other departments"),
                        othersPeople, others));
            }
            Row first = group.get(0);
            shown.add(new ShadowSummary.Service(first.service(), categoryIn(language, first.category()), (int) people,
                    group.size(),
                    group.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add), first.currency(),
                    group.stream().map(Row::paidOn).max(Comparator.naturalOrder()).orElse(null), departments));
        }
        shown.sort(Comparator.comparing(ShadowSummary.Service::people).reversed()
                .thenComparing(ShadowSummary.Service::total, Comparator.reverseOrder()));
        return new ShadowSummary(MIN_PEOPLE, shown, hidden);
    }

    /** An erased employee's reports go: the key finds them although the rows name nobody. */
    int erase(UUID tenantId, UUID employeeId) {
        return jdbc.sql("delete from shadow_spend where reporter = ?").param(pseudonym(tenantId, employeeId)).update();
    }
}
