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

@Repository
class LicenseRepository {

    private static final String SELECT = """
            select r.id, r.employee_id, e.name as employee_name, r.subscription_id, s.product, v.name as vendor,
                   r.reason, r.status, r.idle_license_id, l.last_active as idle_since, r.decided_by, r.decided_at,
                   r.decision_comment, r.created_at
            from license_request r join employee e on e.id = r.employee_id
            join subscription s on s.id = r.subscription_id join vendor v on v.id = s.vendor_id
            left join license_assignment l on l.id = r.idle_license_id""";

    private static final RowMapper<LicenseRequest> ROW = (rs, i) -> {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new LicenseRequest(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getObject("subscription_id", UUID.class), rs.getString("product"),
                rs.getString("vendor"), rs.getString("reason"), rs.getString("status"),
                rs.getObject("idle_license_id") != null, rs.getObject("idle_since", LocalDate.class),
                rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
                rs.getString("decision_comment"), rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    LicenseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, UUID employeeId, UUID subscriptionId, String reason, String status,
            UUID idleLicense, Instant at) {
        jdbc.sql("""
                insert into license_request (id, tenant_id, employee_id, subscription_id, reason, status,
                                             idle_license_id, decided_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, tenantId, employeeId, subscriptionId, reason == null || reason.isBlank() ? null
                        : reason.strip(), status, idleLicense, "granted".equals(status) ? Timestamp.from(at) : null,
                        Timestamp.from(at))
                .update();
    }

    Optional<LicenseRequest> find(UUID id) {
        return jdbc.sql(SELECT + " where r.id = ?").param(id).query(ROW).optional();
    }

    /** Locks the request; the idle license it would take back may be null, a missing request throws. */
    UUID lockIdle(UUID id) {
        List<UUID[]> rows = jdbc.sql("select idle_license_id from license_request where id = ? for update").param(id)
                .query((rs, i) -> new UUID[] {rs.getObject("idle_license_id", UUID.class)}).list();
        if (rows.isEmpty()) {
            throw CabinetProblem.LICENSE_REQUEST_NOT_FOUND.failure();
        }
        return rows.get(0)[0];
    }

    List<LicenseRequest> of(UUID employeeId) {
        return jdbc.sql(SELECT + " where r.employee_id = ? order by r.created_at desc").param(employeeId)
                .query(ROW).list();
    }

    List<LicenseRequest> all() {
        return jdbc.sql(SELECT + " order by (r.status = 'pending') desc, r.created_at desc limit 500").query(ROW)
                .list();
    }

    boolean pendingFor(UUID employeeId, UUID subscriptionId) {
        return jdbc.sql("""
                select count(*) from license_request
                where employee_id = ? and subscription_id = ? and status = 'pending'""")
                .params(employeeId, subscriptionId).query(Integer.class).single() > 0;
    }

    void decide(UUID id, String status, String by, Instant at, String comment) {
        jdbc.sql("""
                update license_request set status = ?, decided_by = ?, decided_at = ?, decision_comment = ?
                where id = ?""")
                .params(status, by, Timestamp.from(at), comment == null || comment.isBlank() ? null : comment.strip(),
                        id)
                .update();
    }

    /** A license taken back is gone: requests pointing at it point nowhere. */
    void forgetIdle(UUID licenseId) {
        jdbc.sql("update license_request set idle_license_id = null where idle_license_id = ? and status <> 'pending'")
                .param(licenseId).update();
    }
}
