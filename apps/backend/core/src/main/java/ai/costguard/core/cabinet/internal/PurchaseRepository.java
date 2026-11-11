package ai.costguard.core.cabinet.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PurchaseRepository {

    private static final String SELECT = """
            select p.id, p.employee_id, e.name as employee_name, p.product, p.vendor, p.amount, p.currency,
                   p.period, p.reason, p.file_name, p.status, s.product as matched_product, lr.status as license_status,
                   p.decided_by, p.decided_at, p.decision_comment, p.created_at, p.file_key
            from purchase_request p join employee e on e.id = p.employee_id
            left join subscription s on s.id = p.subscription_id
            left join license_request lr on lr.id = p.license_request_id""";

    private static final RowMapper<PurchaseRequest> ROW = (rs, i) -> {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new PurchaseRequest(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getString("product"), rs.getString("vendor"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("period"), rs.getString("reason"),
                rs.getString("file_name"), rs.getString("status"), rs.getString("matched_product"),
                rs.getString("license_status"), rs.getString("decided_by"),
                decided == null ? null : decided.toInstant(), rs.getString("decision_comment"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    PurchaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record New(UUID id, UUID employeeId, String product, String vendor, BigDecimal amount, String currency,
            String period, String reason, String fileKey, String fileName, String status, UUID subscriptionId,
            UUID licenseRequestId, String decisionComment, Instant at) {
    }

    void insert(UUID tenantId, New n) {
        boolean decided = !"pending".equals(n.status());
        jdbc.sql("""
                insert into purchase_request (id, tenant_id, employee_id, product, vendor, amount, currency, period,
                                              reason, file_key, file_name, status, subscription_id,
                                              license_request_id, decided_by, decided_at, decision_comment,
                                              created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(n.id(), tenantId, n.employeeId(), n.product(), n.vendor(), n.amount(), n.currency(),
                        n.period(), n.reason(), n.fileKey(), n.fileName(), n.status(), n.subscriptionId(),
                        n.licenseRequestId(), decided ? "CostGuard" : null,
                        decided ? Timestamp.from(n.at()) : null, n.decisionComment(), Timestamp.from(n.at()))
                .update();
    }

    Optional<PurchaseRequest> find(UUID id) {
        return jdbc.sql(SELECT + " where p.id = ?").param(id).query(ROW).optional();
    }

    Optional<String> fileKey(UUID id) {
        return jdbc.sql("select file_key from purchase_request where id = ? and file_key is not null").param(id)
                .query(String.class).optional();
    }

    List<PurchaseRequest> of(UUID employeeId) {
        return jdbc.sql(SELECT + " where p.employee_id = ? order by p.created_at desc").param(employeeId)
                .query(ROW).list();
    }

    List<PurchaseRequest> all() {
        return jdbc.sql(SELECT + " order by (p.status = 'pending') desc, p.created_at desc limit 500").query(ROW)
                .list();
    }

    /** Locks the request for a decision; false when it is not pending any more. */
    boolean lockPending(UUID id) {
        return jdbc.sql("select status from purchase_request where id = ? for update").param(id)
                .query(String.class).optional().map("pending"::equals).orElseThrow(
                        CabinetProblem.PURCHASE_NOT_FOUND::failure);
    }

    void decide(UUID id, String status, String by, Instant at, String comment) {
        jdbc.sql("""
                update purchase_request set status = ?, decided_by = ?, decided_at = ?, decision_comment = ?
                where id = ?""")
                .params(status, by, Timestamp.from(at), comment == null || comment.isBlank() ? null : comment.strip(),
                        id)
                .update();
    }

    List<String> keysOf(UUID employeeId) {
        return jdbc.sql("select file_key from purchase_request where employee_id = ? and file_key is not null")
                .param(employeeId).query(String.class).list();
    }

    int deleteOf(UUID employeeId) {
        return jdbc.sql("delete from purchase_request where employee_id = ?").param(employeeId).update();
    }
}
