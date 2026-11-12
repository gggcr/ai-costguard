package ai.costguard.core.cabinet.internal;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DocumentRepository {

    private static final RowMapper<StoredDocument> ROW = (rs, i) -> new StoredDocument(
            rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class), rs.getString("kind"),
            rs.getString("title"), rs.getString("number"), rs.getObject("signed_on", LocalDate.class),
            rs.getString("file_key"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size"),
            rs.getString("uploaded_by"), rs.getTimestamp("created_at").toInstant(),
            List.of((String[]) rs.getArray("signers").getArray()));

    private static final RowMapper<DocumentSignature> SIGNATURE = (rs, i) -> new DocumentSignature(
            rs.getString("party"), rs.getString("signer_name"), rs.getString("signer_email"), rs.getString("method"),
            rs.getString("document_sha256"), rs.getTimestamp("signed_at").toInstant());

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, StoredDocument d) {
        jdbc.sql("""
                insert into employee_document (id, tenant_id, employee_id, kind, title, number, signed_on, file_key,
                                               file_name, content_type, size, uploaded_by, created_at, signers)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(d.id(), tenantId, d.employeeId(), d.kind(), d.title(), d.number(), d.signedOn(),
                        d.fileKey(), d.fileName(), d.contentType(), d.size(), d.uploadedBy(),
                        Timestamp.from(d.createdAt()), d.signers().toArray(String[]::new))
                .update();
    }

    Optional<StoredDocument> lock(UUID id) {
        return jdbc.sql("select * from employee_document where id = ? for update").param(id).query(ROW).optional();
    }

    List<DocumentSignature> signatures(UUID documentId) {
        return jdbc.sql("select * from document_signature where document_id = ? order by signed_at")
                .param(documentId).query(SIGNATURE).list();
    }

    /** {@code signerAccountId}: the CostGuard account that signed, found by the token's subject. */
    void sign(UUID tenantId, UUID documentId, DocumentSignature s, UUID signerAccountId) {
        jdbc.sql("""
                insert into document_signature (id, tenant_id, document_id, party, signer_name, signer_email, method,
                                                document_sha256, signed_at, signer_account_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), tenantId, documentId, s.party(), s.signerName(), s.signerEmail(),
                        s.method(), s.documentSha256(), Timestamp.from(s.signedAt()), signerAccountId)
                .update();
    }

    /** Documents where one of these sides must sign and has not, oldest first. */
    List<StoredDocument> awaiting(List<String> parties) {
        return jdbc.sql("""
                select d.* from employee_document d
                where exists (select 1 from unnest(d.signers) p
                              where p = any(?) and not exists (select 1 from document_signature s
                                                               where s.document_id = d.id and s.party = p))
                order by d.created_at limit 500""").param(parties.toArray(String[]::new)).query(ROW).list();
    }

    List<StoredDocument> of(UUID employeeId) {
        return jdbc.sql("select * from employee_document where employee_id = ? order by created_at desc, id")
                .param(employeeId).query(ROW).list();
    }

    Optional<StoredDocument> find(UUID id) {
        return jdbc.sql("select * from employee_document where id = ?").param(id).query(ROW).optional();
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from employee_document where id = ?").param(id).update() == 1;
    }

    List<String> keysOf(UUID employeeId) {
        return jdbc.sql("select file_key from employee_document where employee_id = ?").param(employeeId)
                .query(String.class).list();
    }

    int countOf(UUID employeeId) {
        return jdbc.sql("select count(*) from employee_document where employee_id = ?").param(employeeId)
                .query(Integer.class).single();
    }
}
