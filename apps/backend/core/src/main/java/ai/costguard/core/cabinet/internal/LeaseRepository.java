package ai.costguard.core.cabinet.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Аренда лицензий на срок and the reminders before auto-renewal (ADR 0066). */
@Repository
class LeaseRepository {

    private static final String SELECT = """
            select l.license_id, l.subscription_id, s.product, l.employee_id, e.name as employee_name, l.lender_id,
                   l.ends_on
            from license_lease l join subscription s on s.id = l.subscription_id
            join employee e on e.id = l.employee_id""";

    private static final RowMapper<Lease> ROW = (rs, i) -> new Lease(rs.getObject("license_id", UUID.class),
            rs.getObject("subscription_id", UUID.class), rs.getString("product"),
            rs.getObject("employee_id", UUID.class), rs.getString("employee_name"),
            rs.getObject("lender_id", UUID.class), rs.getObject("ends_on", LocalDate.class));

    private final JdbcClient jdbc;

    LeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID licenseId, UUID subscriptionId, UUID employeeId, UUID lenderId, LocalDate endsOn,
            Instant at) {
        jdbc.sql("""
                insert into license_lease (license_id, tenant_id, subscription_id, employee_id, lender_id, ends_on,
                                           created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (license_id) do update set ends_on = excluded.ends_on, lender_id = excluded.lender_id,
                                                       reminded = false""")
                .params(licenseId, tenantId, subscriptionId, employeeId, lenderId, endsOn, Timestamp.from(at))
                .update();
    }

    /** What I hold for a while. */
    List<Lease> borrowedBy(UUID employeeId) {
        return jdbc.sql(SELECT + " where l.employee_id = ? order by l.ends_on").param(employeeId).query(ROW).list();
    }

    /** What colleagues hold of mine for a while. */
    List<Lease> lentBy(UUID employeeId) {
        return jdbc.sql(SELECT + " where l.lender_id = ? order by l.ends_on").param(employeeId).query(ROW).list();
    }

    Optional<Lease> lock(UUID licenseId) {
        return jdbc.sql(SELECT + " where l.license_id = ? for update of l").param(licenseId).query(ROW).optional();
    }

    List<Lease> endedBy(LocalDate day) {
        return jdbc.sql(SELECT + " where l.ends_on <= ? order by l.ends_on for update of l skip locked").param(day)
                .query(ROW).list();
    }

    /** Leases ending on this day nobody was reminded of; marks them reminded. */
    List<Lease> remindOn(LocalDate day) {
        List<Lease> due = jdbc.sql(SELECT + " where l.ends_on = ? and not l.reminded for update of l skip locked")
                .param(day).query(ROW).list();
        due.forEach(l -> jdbc.sql("update license_lease set reminded = true where license_id = ?")
                .param(l.licenseId()).update());
        return due;
    }

    void delete(UUID licenseId) {
        jdbc.sql("delete from license_lease where license_id = ?").param(licenseId).update();
    }

    /** True the first time for this license and renewal date. */
    boolean remindRenewal(UUID tenantId, UUID licenseId, LocalDate renewalDate, Instant at) {
        return jdbc.sql("""
                insert into renewal_reminder (tenant_id, license_id, renewal_date, sent_at) values (?, ?, ?, ?)
                on conflict do nothing""").params(tenantId, licenseId, renewalDate, Timestamp.from(at)).update() == 1;
    }

    List<UUID> tenants() {
        return jdbc.sql("select tenant_id from cabinet_tenant").query(UUID.class).list();
    }
}
