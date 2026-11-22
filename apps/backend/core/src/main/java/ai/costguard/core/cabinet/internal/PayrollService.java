package ai.costguard.core.cabinet.internal;

import ai.costguard.core.notifications.PersonalNotices;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.core.shared.names.TimeZones;
import ai.costguard.core.shared.names.WrittenValues;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Зарплата (ADR 0054): the employee sees their own payments and the pay days ahead; owner and finance load the
 * payments (a file, or the 1С:ЗУП connector over REST) and see everyone's. On the pay day the employee is told.
 */
@Service
class PayrollService {

    private static final Logger log = LoggerFactory.getLogger(PayrollService.class);
    /** The hour of the company's morning the pay-day notices go out at (ADR 0073). */
    static final int PAY_DAY_HOUR = 9;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Most Russian companies: the advance on the 25th, the salary of the month on the 10th of the next one. */
    static final PayrollSchedule DEFAULT = new PayrollSchedule(25, 10);

    private final PayrollRepository payroll;
    private final EmployeeDirectory employees;
    private final PersonalNotices notices;
    private final Whoami whoami;
    private final TenantSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    PayrollService(PayrollRepository payroll, EmployeeDirectory employees, PersonalNotices notices, Whoami whoami,
            TenantSession session, ApplicationEventPublisher events, Clock clock,
            PlatformTransactionManager transactions) {
        this.payroll = payroll;
        this.employees = employees;
        this.notices = notices;
        this.whoami = whoami;
        this.session = session;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    /** The company's time zone (ADR 0073); the session must be bound to the tenant. */
    private ZoneId zone() {
        return employees.company().map(EmployeeDirectory.CompanyCard::zone).orElse(TimeZones.LEGACY);
    }

    @Transactional(readOnly = true)
    MyPayroll mine(UUID tenantId, Jwt jwt) {
        session.bind(tenantId);
        UUID me = whoami.employee(jwt);
        PayrollSchedule s = payroll.schedule().orElse(DEFAULT);
        return new MyPayroll(s, upcoming(s, today()), payroll.of(me, today().minusMonths(24)));
    }

    /** The next advance and the next salary from {@code from}, a weekend day moved back to Friday. */
    static List<PayDate> upcoming(PayrollSchedule s, LocalDate from) {
        List<PayDate> out = new ArrayList<>();
        out.add(new PayDate("advance", next(s.advanceDay(), from)));
        out.add(new PayDate("salary", next(s.salaryDay(), from)));
        out.sort(Comparator.comparing(PayDate::date));
        return out;
    }

    private static LocalDate next(int day, LocalDate from) {
        for (int m = 0; m < 3; m++) {
            YearMonth month = YearMonth.from(from).plusMonths(m);
            LocalDate d = workday(month.atDay(Math.min(day, month.lengthOfMonth())));
            if (!d.isBefore(from)) {
                return d;
            }
        }
        return from;
    }

    private static LocalDate workday(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    @Transactional(readOnly = true)
    PayrollSchedule schedule(UUID tenantId) {
        session.bind(tenantId);
        return payroll.schedule().orElse(DEFAULT);
    }

    @Transactional
    PayrollSchedule saveSchedule(UUID tenantId, PayrollSchedule s) {
        session.bind(tenantId);
        payroll.saveSchedule(tenantId, s, clock.instant());
        events.publishEvent(ActionTaken.of(tenantId, "payroll.schedule", "payroll_schedule", tenantId,
                ActionTaken.details("advanceDay", s.advanceDay(), "salaryDay", s.salaryDay())));
        return s;
    }

    @Transactional(readOnly = true)
    List<PayrollPayment> ofMonth(UUID tenantId, YearMonth month) {
        session.bind(tenantId);
        return payroll.ofPeriod(month.atDay(1));
    }

    /**
     * A batch from the connector or a person; every payment is matched to an employee by e-mail, then by name. A
     * payment goes only to the one employee of the company its address names; an address of several (they differ in
     * case only) pays nobody, nor does the name rescue it, and an unknown or broken address with no unique name pays
     * nobody either (finding R1 of 2026-11-12). Such a payment is kept in payroll_unmatched with its reason, once
     * however often it comes, and leaves it when a later load matches it.
     */
    @Transactional
    PayrollImport store(UUID tenantId, PaymentBatch batch) {
        session.bind(tenantId);
        List<PayrollImport.Skipped> skipped = new ArrayList<>();
        int stored = 0;
        for (int i = 0; i < batch.payments().size(); i++) {
            ReportedPayment p = batch.payments().get(i);
            LocalDate period = p.period().withDayOfMonth(1);
            BigDecimal amount = p.amount().setScale(2, RoundingMode.HALF_UP);
            String currency = p.currency() == null ? currency() : p.currency();
            String fingerprint = fingerprint(p, period, amount, currency);
            Match who = person(p.employeeEmail(), p.employeeName());
            if (who.employee().isEmpty()) {
                payroll.keepUnmatched(tenantId, fingerprint, batch.source(), blankToNull(p.employeeEmail()),
                        blankToNull(p.employeeName()), p.kind(), period, p.payDate(), amount, currency,
                        p.externalKey(), who.reason(), who.candidates(), clock.instant());
                skipped.add(new PayrollImport.Skipped(i + 1, who.reason()));
                continue;
            }
            payroll.upsert(tenantId, who.employee().get(), p.kind(), period, p.payDate(), amount, currency,
                    batch.source(), p.externalKey(), clock.instant());
            payroll.resolveUnmatched(fingerprint);
            stored++;
        }
        events.publishEvent(ActionTaken.of(tenantId, "payroll.loaded", "payroll_payment", tenantId,
                ActionTaken.details("source", batch.source(), "stored", stored, "skipped", skipped.size())));
        return new PayrollImport(stored, skipped);
    }

    /** A pay sheet of finance, .xlsx or .csv (see {@link PayrollFile}). */
    @Transactional
    PayrollImport load(UUID tenantId, String fileName, byte[] content) {
        session.bind(tenantId);
        List<PayrollImport.Skipped> skipped = new ArrayList<>();
        List<ReportedPayment> good = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<PayrollFile.Row> rows = PayrollFile.read(fileName, content);
        var company = PayrollFile.style(fileName, employees.company().map(EmployeeDirectory.CompanyCard::country)
                .orElse(null));
        var amounts = WrittenValues.amounts(rows.stream().map(PayrollFile.Row::amount).toList(), company);
        var days = WrittenValues.dates(rows.stream().flatMap(r -> java.util.stream.Stream.of(r.payDate(), r.period()))
                .toList(), company);
        for (PayrollFile.Row row : rows) {
            LocalDate payDate = PayrollFile.date(row.payDate(), days);
            LocalDate period = row.period().isBlank() ? payDate : PayrollFile.date(row.period(), days);
            BigDecimal amount = PayrollFile.amount(row.amount(), amounts);
            if (payDate == null || period == null) {
                skipped.add(new PayrollImport.Skipped(row.line(), "bad date"));
                continue;
            }
            if (amount == null || amount.signum() < 0) {
                skipped.add(new PayrollImport.Skipped(row.line(), "bad amount"));
                continue;
            }
            String person = row.person();
            good.add(new ReportedPayment(person.contains("@") ? person : null, person.contains("@") ? null : person,
                    PayrollFile.kind(row.kind()), period, payDate, amount, null, null));
            lines.add(row.line());
        }
        if (good.isEmpty()) {
            return new PayrollImport(0, skipped);
        }
        PayrollImport stored = store(tenantId, new PaymentBatch("excel:" + fileName, good));
        stored.skipped().forEach(s -> skipped.add(new PayrollImport.Skipped(lines.get(s.row() - 1), s.reason())));
        return new PayrollImport(stored.stored(), skipped);
    }

    /** Who a payment is for, or why nobody: the reason and how many employees its address named. */
    record Match(Optional<UUID> employee, String reason, int candidates) {

        static Match of(UUID employee) {
            return new Match(Optional.of(employee), null, 1);
        }

        static Match none(String reason, int candidates) {
            return new Match(Optional.empty(), reason, candidates);
        }
    }

    /**
     * Who a payment is for (finding R1 of 2026-11-12 and its remainder R1.5.1). A payment that carries an address is
     * matched by the address only: a broken one is {@code bad email}, an unknown one {@code employee not found}, one of
     * several employees {@code ambiguous employee}, and the name never stands in for it, since a mistyped address
     * with someone else's name would pay that someone. Only a payment with no address at all is matched by the name,
     * and only to the one employee who has it.
     */
    private Match person(String email, String name) {
        if (email != null && !email.isBlank()) {
            EmployeeDirectory.EmployeeMatch m = employees.matchByEmail(email);
            return switch (m.outcome()) {
                case MATCHED -> Match.of(m.employeeId());
                case AMBIGUOUS -> Match.none("ambiguous employee", m.candidates());
                case INVALID -> Match.none("bad email", 0);
                case NOT_FOUND -> Match.none("employee not found", 0);
            };
        }
        if (name != null && !name.isBlank()) {
            EmployeeDirectory.EmployeeMatch m = employees.matchByName(name);
            return switch (m.outcome()) {
                case MATCHED -> Match.of(m.employeeId());
                case AMBIGUOUS -> Match.none("ambiguous employee", m.candidates());
                case INVALID, NOT_FOUND -> Match.none("employee not found", 0);
            };
        }
        return Match.none("employee not found", 0);
    }

    /**
     * The same payment reported again is the same row: who it names (the address as the application normalises it,
     * and the name), kind, month, day, amount and currency. Not the source, so a sheet and the connector agree.
     */
    static String fingerprint(ReportedPayment p, LocalDate period, BigDecimal amount, String currency) {
        String email = p.employeeEmail() == null ? "" : p.employeeEmail().strip().toLowerCase(Locale.ROOT);
        String name = p.employeeName() == null ? "" : p.employeeName().strip();
        String text = String.join("\u001f", email, name, p.kind().name(), period.toString(), p.payDate().toString(),
                amount.toPlainString(), currency);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Transactional(readOnly = true)
    List<PayrollUnmatched> unmatched(UUID tenantId) {
        session.bind(tenantId);
        return payroll.unmatched();
    }

    private String currency() {
        return employees.company().map(EmployeeDirectory.CompanyCard::currency).orElse("RUB");
    }

    /**
     * Every morning of each company, at {@value #PAY_DAY_HOUR}:00 its own time: tell each employee about the payments
     * of the day (and any day missed while the job was down). The job runs every hour and takes the companies whose
     * morning it is.
     */
    @Scheduled(cron = "${costguard.payroll.notify-cron:0 0 * * * *}")
    void payDay() {
        for (UUID tenant : tx.execute(s -> payroll.tenants())) {
            try {
                tx.executeWithoutResult(s -> {
                    session.bind(tenant);
                    if (java.time.ZonedDateTime.now(clock.withZone(zone())).getHour() == PAY_DAY_HOUR) {
                        tellDue(tenant);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Pay-day notices of tenant {} failed", tenant, e);
            }
        }
    }

    int tellDue(UUID tenantId) {
        session.bind(tenantId);
        int told = 0;
        for (PayrollPayment p : payroll.due(today())) {
            notices.send(tenantId, p.employeeId(), text(p), "/cabinet/pay", "good");
            payroll.notified(p.id());
            told++;
        }
        return told;
    }

    private static final DecimalFormat MONEY = money();

    /** 25 000,00: a plain space between thousands, which every mail client and Telegram shows the same. */
    private static DecimalFormat money() {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", symbols);
    }

    private static String text(PayrollPayment p) {
        String what = switch (p.kind()) {
            case "advance" -> "Аванс";
            case "salary" -> "Зарплата";
            case "vacation_pay" -> "Отпускные";
            case "bonus" -> "Премия";
            default -> "Выплата";
        };
        String sum = MONEY.format(p.amount()) + ("RUB".equals(p.currency()) ? " ₽" : " " + p.currency());
        return what + " " + DAY.format(p.payDate()) + ": " + sum;
    }
}
