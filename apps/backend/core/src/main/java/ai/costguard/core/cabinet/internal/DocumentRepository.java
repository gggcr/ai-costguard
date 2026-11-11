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
            rs.getString("uploaded_by"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, StoredDocument d) {
        jdbc.sql("""
                insert into employee_document (id, tenant_id, employee_id, kind, title, number, signed_on, file_key,
                                               file_name, content_type, size, uploaded_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(d.id(), tenantId, d.employeeId(), d.kind(), d.title(), d.number(), d.signedOn(),
                        d.fileKey(), d.fileName(), d.contentType(), d.size(), d.uploadedBy(),
                        Timestamp.from(d.createdAt()))
                .update();
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
