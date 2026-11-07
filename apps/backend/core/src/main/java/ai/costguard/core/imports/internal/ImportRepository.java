package ai.costguard.core.imports.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ImportRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    ImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(String value, TypeReference<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    List<ReviewTask> tasks(UUID jobId) {
        return jdbc.sql("select * from import_review_task where job_id = ? order by row, field").param(jobId)
                .query((rs, n) -> new ReviewTask(rs.getObject("id", UUID.class), rs.getInt("row"), rs.getString("field"),
                        rs.getString("value"), rs.getBigDecimal("confidence"), rs.getBoolean("resolved"),
                        rs.getString("resolved_value")))
                .list();
    }

    private List<ImportJob> query(String where, Object... params) {
        return jdbc.sql("select * from import_job " + where).params(params)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ImportJob(id, rs.getString("file_name"), ImportType.valueOf(rs.getString("type")),
                            rs.getObject("vendor_id", UUID.class), rs.getTimestamp("uploaded_at").toInstant(),
                            rs.getString("uploaded_by"), ImportStatus.valueOf(rs.getString("status")), rs.getInt("rows"),
                            rs.getBigDecimal("total"), strings(rs.getArray("columns")),
                            read(rs.getString("mapping"), new TypeReference<Map<String, String>>() { }),
                            read(rs.getString("errors"), new TypeReference<List<RowError>>() { }), List.of(),
                            rs.getBoolean("template_saved"), rs.getString("failure"), rs.getString("sha256"));
                })
                .list().stream()
                .map(j -> new ImportJob(j.id(), j.fileName(), j.type(), j.vendorId(), j.uploadedAt(), j.uploadedBy(),
                        j.status(), j.rows(), j.total(), j.columns(), j.mapping(), j.errors(), tasks(j.id()),
                        j.templateSaved(), j.failure(), j.sha256()))
                .toList();
    }

    List<ImportJob> list() {
        return query("order by uploaded_at desc, id");
    }

    Optional<ImportJob> find(UUID id) {
        return query("where id = ?", id).stream().findFirst();
    }

    Optional<ImportJob> lock(UUID id) {
        return query("where id = ? for update", id).stream().findFirst();
    }

    Optional<ImportJob> bySha(String sha256) {
        return query("where sha256 = ?", sha256).stream().findFirst();
    }

    boolean insert(UUID tenantId, UUID id, String fileName, ImportType type, UUID vendorId, Timestamp at, String by,
            String sha, String key, long size) {
        return jdbc.sql("""
                insert into import_job (id, tenant_id, file_name, type, vendor_id, uploaded_at, uploaded_by, status,
                                        sha256, storage_key, size_bytes)
                values (?, ?, ?, ?, ?, ?, ?, 'uploaded', ?, ?, ?)
                on conflict (tenant_id, sha256) do nothing
                """).params(id, tenantId, fileName, type.name(), vendorId, at, by, sha, key, size).update() == 1;
    }

    void parsed(UUID id, List<String> columns, Map<String, String> mapping, int rows, BigDecimal total,
            List<RowError> errors) {
        jdbc.sql("""
                update import_job set status = 'mapping', columns = ?::text[], mapping = ?::jsonb, rows = ?, total = ?,
                                      errors = ?::jsonb
                where id = ?
                """).params(literal(columns), json(mapping), rows, total, json(errors), id).update();
    }

    void insertTask(UUID tenantId, UUID jobId, int row, String field, String value, double confidence) {
        jdbc.sql("""
                insert into import_review_task (id, tenant_id, job_id, row, field, value, confidence)
                values (?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), tenantId, jobId, row, field, value, BigDecimal.valueOf(confidence)).update();
    }

    void setMapping(UUID id, Map<String, String> mapping, boolean template, ImportStatus status) {
        jdbc.sql("update import_job set mapping = ?::jsonb, template_saved = ?, status = ? where id = ?")
                .params(json(mapping), template, status.name(), id).update();
    }

    boolean resolve(UUID jobId, UUID taskId, String value) {
        return jdbc.sql("update import_review_task set resolved = true, resolved_value = ? where id = ? and job_id = ?")
                .params(value, taskId, jobId).update() == 1;
    }

    void setStatus(UUID id, ImportStatus status, String failure) {
        jdbc.sql("update import_job set status = ?, failure = ? where id = ?").params(status.name(), failure, id).update();
    }

    private static String literal(List<String> values) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append('"')
                    .append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return out.append('}').toString();
    }
}
