package ai.costguard.core.cabinet.internal;

import ai.costguard.core.software.LicensePool;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сколько сэкономил кабинет (ADR 0061): seats not bought because a free or idle license was given from the pool
 * (the seat's monthly price), and purchases refused because the company already had the program (their amount a
 * month: a yearly one divided by 12, a one-off counted once). By the month of the decision, in the company's terms.
 */
@Service
class SavingsService {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final LicensePool pool;
    private final Clock clock;

    SavingsService(JdbcClient jdbc, TenantSession session, LicensePool pool, Clock clock) {
        this.jdbc = jdbc;
        this.session = session;
        this.pool = pool;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CabinetSavings savings(UUID tenantId, int months) {
        session.bind(tenantId);
        YearMonth now = YearMonth.now(clock.withZone(MOSCOW));
        YearMonth first = now.minusMonths(months - 1L);
        Timestamp from = Timestamp.from(first.atDay(1).atStartOfDay(MOSCOW).toInstant());
        Map<UUID, BigDecimal> prices = pool.monthlySeatPrices();
        TreeMap<YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (YearMonth m = first; !m.isAfter(now); m = m.plusMonths(1)) {
            byMonth.put(m, BigDecimal.ZERO);
        }

        record Seat(UUID subscription, boolean idle, java.time.Instant at) {
        }
        List<Seat> seats = jdbc.sql("""
                        select subscription_id, idle_license_id is not null as idle, coalesce(decided_at, created_at) as at
                        from license_request where status in ('granted', 'approved')
                          and coalesce(decided_at, created_at) >= ?
                          -- a seat given instead of a purchase counts once, as the purchase not made
                          and id not in (select license_request_id from purchase_request
                                         where license_request_id is not null)""").param(from)
                .query((rs, i) -> new Seat(rs.getObject("subscription_id", UUID.class), rs.getBoolean("idle"),
                        rs.getTimestamp("at").toInstant()))
                .list();
        BigDecimal seatMonthly = BigDecimal.ZERO;
        int reassigned = 0;
        for (Seat s : seats) {
            BigDecimal price = prices.getOrDefault(s.subscription(), BigDecimal.ZERO);
            seatMonthly = seatMonthly.add(price);
            reassigned += s.idle() ? 1 : 0;
            byMonth.merge(YearMonth.from(s.at().atZone(MOSCOW)), price, BigDecimal::add);
        }

        record Refused(BigDecimal amount, String period, java.time.Instant at) {
        }
        List<Refused> refused = jdbc.sql("""
                        select amount, period, coalesce(decided_at, created_at) as at from purchase_request
                        where status = 'has_license' and coalesce(decided_at, created_at) >= ?""").param(from)
                .query((rs, i) -> new Refused(rs.getBigDecimal("amount"), rs.getString("period"),
                        rs.getTimestamp("at").toInstant()))
                .list();
        BigDecimal purchaseMonthly = BigDecimal.ZERO;
        for (Refused r : refused) {
            BigDecimal monthly = "year".equals(r.period())
                    ? r.amount().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP) : r.amount();
            purchaseMonthly = purchaseMonthly.add(monthly);
            byMonth.merge(YearMonth.from(r.at().atZone(MOSCOW)), monthly, BigDecimal::add);
        }

        List<CabinetSavings.SavedMonth> series = new ArrayList<>();
        byMonth.forEach((m, v) -> series.add(new CabinetSavings.SavedMonth(m.toString(), v)));
        LocalDate since = first.atDay(1);
        return new CabinetSavings(since, seats.size(), reassigned, seatMonthly, refused.size(), purchaseMonthly,
                seatMonthly.add(purchaseMonthly), series);
    }
}
