package ai.costguard.core.approvals.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class ApprovalRepository {

    private static final String COLUMNS = """
            id, operation_id, agent_id, description, amount, reason, created_at, expires_at, status, escalated_at,
            decided_by, comment, notary_hash, near_tx_hash
            """;

    private static final RowMapper<Approval> APPROVAL = (rs, n) -> new Approval(rs.getObject("id", UUID.class),
            rs.getObject("operation_id", UUID.class), rs.getObject("agent_id", UUID.class),
            rs.getString("description"), rs.getBigDecimal("amount").stripTrailingZeros(), rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            ApprovalStatus.valueOf(rs.getString("status")), rs.getTimestamp("escalated_at") != null,
            rs.getString("decided_by"), rs.getString("comment"), rs.getString("notary_hash"),
            rs.getString("near_tx_hash"));

    private static final RowMapper<DueApproval> DUE = (rs, n) -> new DueApproval(
            rs.getObject("approval_id", UUID.class), rs.getObject("tenant_id", UUID.class));

    private final JdbcClient jdbc;

    ApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Approval> list(ApprovalStatus status) {
        return jdbc.sql("select " + COLUMNS + """
                 from approval_request
                 where (cast(:status as text) is null or status = :status)
                 order by created_at desc, id
                """).param("status", status == null ? null : status.name()).query(APPROVAL).list();
    }

    Optional<Approval> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from approval_request where id = ?").param(id)
                .query(APPROVAL).optional();
    }

    Optional<Approval> findForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from approval_request where id = ? for update").param(id)
                .query(APPROVAL).optional();
    }

    Optional<UUID> byOperation(UUID operationId) {
        return jdbc.sql("select id from approval_request where operation_id = ?").param(operationId)
                .query(UUID.class).optional();
    }

    /** Returns false if the operation already has a request. */
    boolean insert(UUID tenantId, UUID id, UUID operationId, UUID agentId, String description, BigDecimal amount,
            String reason, Instant createdAt, Instant expiresAt) {
        return jdbc.sql("""
                insert into approval_request (id, tenant_id, operation_id, agent_id, description, amount, reason,
                                              status, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                on conflict (tenant_id, operation_id) do nothing
                """).params(id, tenantId, operationId, agentId, description, amount, reason,
                Timestamp.from(createdAt), Timestamp.from(expiresAt)).update() == 1;
    }

    void close(UUID id, ApprovalStatus status, String decidedBy, String comment) {
        jdbc.sql("update approval_request set status = ?, decided_by = ?, comment = ? where id = ?")
                .params(status.name(), decidedBy, comment, id).update();
    }

    void setNotaryHash(UUID id, String hash) {
        jdbc.sql("update approval_request set notary_hash = ? where id = ?").params(hash, id).update();
    }

    Optional<String> notaryHashOfOperation(UUID operationId) {
        return jdbc.sql("select notary_hash from approval_request where operation_id = ? and notary_hash is not null")
                .param(operationId).query(String.class).optional();
    }

    /** The first transaction reported wins: a repeat changes nothing. */
    void notarized(UUID operationId, String txHash, Instant at) {
        jdbc.sql("""
                update approval_request set near_tx_hash = ?, notarized_at = ?
                where operation_id = ? and near_tx_hash is null
                """).params(txHash, Timestamp.from(at), operationId).update();
    }

    void markEscalated(UUID id, Instant at) {
        jdbc.sql("update approval_request set escalated_at = ? where id = ?").params(Timestamp.from(at), id).update();
    }

    void scheduleTimer(UUID approvalId, UUID tenantId, Instant escalateAt, Instant expiresAt) {
        jdbc.sql("insert into approval_timer (approval_id, tenant_id, escalate_at, expires_at) values (?, ?, ?, ?)")
                .params(approvalId, tenantId, Timestamp.from(escalateAt), Timestamp.from(expiresAt)).update();
    }

    void clearEscalation(UUID approvalId) {
        jdbc.sql("update approval_timer set escalate_at = null where approval_id = ?").param(approvalId).update();
    }

    void removeTimer(UUID approvalId) {
        jdbc.sql("delete from approval_timer where approval_id = ?").param(approvalId).update();
    }

    List<DueApproval> dueForExpiry(Instant now, int limit) {
        return jdbc.sql("""
                select approval_id, tenant_id from approval_timer
                where expires_at <= ? order by expires_at limit ?
                """).params(Timestamp.from(now), limit).query(DUE).list();
    }

    List<DueApproval> dueForEscalation(Instant now, int limit) {
        return jdbc.sql("""
                select approval_id, tenant_id from approval_timer
                where escalate_at <= ? order by escalate_at limit ?
                """).params(Timestamp.from(now), limit).query(DUE).list();
    }
}
