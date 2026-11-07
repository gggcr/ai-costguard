package ai.costguard.core.journal.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JournalRepository {

    private static final String COLUMNS = """
            id, operation_id, seq, payload, hash, previous_hash, signature, key_id, issued_at, batch_id
            """;

    private static final RowMapper<StoredReceipt> RECEIPT = (rs, n) -> new StoredReceipt(rs.getObject("id", UUID.class),
            rs.getObject("operation_id", UUID.class), rs.getLong("seq"), rs.getString("payload"), rs.getString("hash"),
            rs.getString("previous_hash"), rs.getString("signature"), rs.getString("key_id"),
            rs.getTimestamp("issued_at").toInstant(), rs.getObject("batch_id", UUID.class));

    private final JdbcClient jdbc;

    JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the chain head if needed and locks it. */
    ChainHead lockHead(UUID tenantId) {
        jdbc.sql("insert into receipt_chain (tenant_id, seq, hash) values (?, 0, ?) on conflict do nothing")
                .params(tenantId, Hashes.GENESIS).update();
        return jdbc.sql("select seq, hash from receipt_chain where tenant_id = ? for update").param(tenantId)
                .query((rs, n) -> new ChainHead(rs.getLong("seq"), rs.getString("hash"))).single();
    }

    void moveHead(UUID tenantId, long seq, String hash) {
        jdbc.sql("update receipt_chain set seq = ?, hash = ? where tenant_id = ?").params(seq, hash, tenantId).update();
    }

    Optional<StoredReceipt> byOperation(UUID operationId) {
        return jdbc.sql("select " + COLUMNS + " from receipt where operation_id = ?").param(operationId)
                .query(RECEIPT).optional();
    }

    void insert(UUID tenantId, StoredReceipt r) {
        jdbc.sql("""
                insert into receipt (id, tenant_id, operation_id, seq, payload, hash, previous_hash, signature,
                                     key_id, issued_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(r.id(), tenantId, r.operationId(), r.seq(), r.payload(), r.hash(), r.previousHash(),
                r.signature(), r.keyId(), Timestamp.from(r.issuedAt())).update();
    }

    List<StoredReceipt> inOrder(long afterSeq, int limit) {
        return jdbc.sql("select " + COLUMNS + " from receipt where seq > ? order by seq limit ?")
                .params(afterSeq, limit).query(RECEIPT).list();
    }

    List<StoredReceipt> byIds(List<UUID> ids) {
        return jdbc.sql("select " + COLUMNS + " from receipt where id = any(?)")
                .param(ids.toArray(UUID[]::new)).query(RECEIPT).list();
    }

    List<String> batchHashes(UUID batchId) {
        return jdbc.sql("select hash from receipt where batch_id = ? order by seq").param(batchId)
                .query(String.class).list();
    }

    boolean batchExists(UUID batchId) {
        return jdbc.sql("select exists (select from anchor_batch where id = ?)").param(batchId)
                .query(Boolean.class).single();
    }

    Optional<Batch> batch(UUID batchId) {
        return jdbc.sql("select id, merkle_root, near_tx_hash from anchor_batch where id = ?").param(batchId)
                .query((rs, n) -> new Batch(rs.getObject("id", UUID.class), rs.getString("merkle_root"),
                        rs.getString("near_tx_hash")))
                .optional();
    }

    void insertBatch(UUID tenantId, UUID id, String root, String tx, Instant at) {
        jdbc.sql("insert into anchor_batch (id, tenant_id, merkle_root, near_tx_hash, anchored_at) values (?, ?, ?, ?, ?)")
                .params(id, tenantId, root, tx, Timestamp.from(at)).update();
    }

    void assignBatch(List<UUID> receiptIds, UUID batchId) {
        jdbc.sql("update receipt set batch_id = ? where id = any(?) and batch_id is null")
                .params(batchId, receiptIds.toArray(UUID[]::new)).update();
    }

    record ChainHead(long seq, String hash) {
    }
}
