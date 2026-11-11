package ai.costguard.core.cabinet.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PayrollRepository {

    private static final RowMapper<PayrollPayment> ROW = (rs, i) -> new PayrollPayment(rs.getObject("id", UUID.class),
            rs.getObject("employee_id", UUID.class), rs.getString("employee_name"), rs.getString("kind"),
            rs.getObject("period", LocalDate.class), rs.getObject("pay_date", LocalDate.class),
            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("source"),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT = """
            select p.id, p.employee_id, e.name as employee_name, p.kind, p.period, p.pay_date, p.amount, p.currency,
                   p.source, p.created_at
            from payroll_payment p join employee e on e.id = p.employee_id""";

    private final JdbcClient jdbc;

    PayrollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PayrollSchedule> schedule() {
        return jdbc.sql("select advance_day, salary_day from payroll_schedule")
                .query((rs, i) -> new PayrollSchedule(rs.getInt("advance_day"), rs.getInt("salary_day"))).optional();
    }

    void saveSchedule(UUID tenantId, PayrollSchedule s, Instant at) {
        jdbc.sql("""
                insert into payroll_schedule (tenant_id, advance_day, salary_day, updated_at) values (?, ?, ?, ?)
                on conflict (tenant_id) do update set advance_day = excluded.advance_day,
                    salary_day = excluded.salary_day, updated_at = excluded.updated_at""")
                .params(tenantId, s.advanceDay(), s.salaryDay(), Timestamp.from(at)).update();
        seen(tenantId);
    }

    /** Stores the payment or replaces the one of the same employee, kind, month and day. */
    void upsert(UUID tenantId, UUID employeeId, PayKind kind, LocalDate period, LocalDate payDate,
            BigDecimal amount, String currency, String source, String externalKey, Instant at) {
        jdbc.sql("""
                insert into payroll_payment (id, tenant_id, employee_id, kind, period, pay_date, amount, currency,
                                             source, external_key, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, employee_id, kind, period, pay_date) do update set
                    amount = excluded.amount, currency = excluded.currency, source = excluded.source,
                    external_key = excluded.external_key""")
                .params(UUID.randomUUID(), tenantId, employeeId, kind.name(), period, payDate, amount, currency,
                        source, externalKey, Timestamp.from(at))
                .update();
        seen(tenantId);
    }

    private void seen(UUID tenantId) {
        jdbc.sql("insert into payroll_tenant (tenant_id) values (?) on conflict do nothing").param(tenantId).update();
    }

    List<PayrollPayment> of(UUID employeeId, LocalDate since) {
        return jdbc.sql(SELECT + " where p.employee_id = ? and p.pay_date >= ? order by p.pay_date desc, p.kind")
                .params(employeeId, since).query(ROW).list();
    }

    List<PayrollPayment> ofPeriod(LocalDate period) {
        return jdbc.sql(SELECT + " where p.period = ? order by e.name, p.pay_date, p.kind").param(period)
                .query(ROW).list();
    }

    /** Payments of the day nobody was told about yet, locked so two nodes do not both tell. */
    List<PayrollPayment> due(LocalDate day) {
        return jdbc.sql(SELECT + " where p.pay_date <= ? and not p.notified for update of p skip locked")
                .param(day).query(ROW).list();
    }

    void notified(UUID id) {
        jdbc.sql("update payroll_payment set notified = true where id = ?").param(id).update();
    }

    List<UUID> tenants() {
        return jdbc.sql("select tenant_id from payroll_tenant").query(UUID.class).list();
    }

    int countOf(UUID employeeId) {
        return jdbc.sql("select count(*) from payroll_payment where employee_id = ?").param(employeeId)
                .query(Integer.class).single();
    }
}
