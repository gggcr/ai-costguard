package ai.costguard.core.connections.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class ConnectionRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The connection with its latest pull, its latest failure and the records of every pull. */
    private static final String VIEW = """
            select c.id, c.type, c.name, c.enabled, c.managed, c.settings::text as settings, c.sync_requested_at,
                   c.created_at,
                   last.started_at as last_started, last.finished_at as last_finished, last.status as last_status,
                   last.error as last_error_now,
                   ok.finished_at as ok_finished, ok.records as ok_records,
                   bad.finished_at as bad_finished, bad.error as bad_error,
                   coalesce((select sum(r.records) from connection_run r where r.connection_id = c.id), 0) as total
            from connection c
            left join lateral (select * from connection_run r where r.connection_id = c.id
                               order by r.finished_at desc limit 1) last on true
            left join lateral (select * from connection_run r where r.connection_id = c.id and r.status = 'SUCCEEDED'
                               order by r.finished_at desc limit 1) ok on true
            left join lateral (select * from connection_run r where r.connection_id = c.id and r.status = 'FAILED'
                               order by r.finished_at desc limit 1) bad on true
            """;

    private static final RowMapper<Connection> CONNECTION = ConnectionRepository::connection;

    private final JdbcClient jdbc;

    ConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Connection connection(ResultSet rs, int n) throws SQLException {
        boolean enabled = rs.getBoolean("enabled");
        Instant requested = at(rs, "sync_requested_at");
        Instant lastStarted = at(rs, "last_started");
        String lastStatus = rs.getString("last_status");
        ConnectionStatus status;
        if (!enabled) {
            status = ConnectionStatus.disconnected;
        } else if (requested != null && (lastStarted == null || lastStarted.isBefore(requested))) {
            status = ConnectionStatus.sync_requested;
        } else if (lastStatus == null) {
            status = ConnectionStatus.pending;
        } else {
            status = "FAILED".equals(lastStatus) ? ConnectionStatus.failing : ConnectionStatus.connected;
        }
        int okRecords = rs.getInt("ok_records");
        Integer recordsLastSync = rs.wasNull() ? null : okRecords;
        return new Connection(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("name"), status,
                enabled, rs.getBoolean("managed"), settings(rs.getString("settings")), at(rs, "ok_finished"),
                recordsLastSync, rs.getLong("total"), rs.getString("bad_error"), at(rs, "bad_finished"), requested,
                at(rs, "created_at"));
    }

    static Map<String, String> settings(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Settings are not a JSON object of strings", e);
        }
    }

    private static String json(Map<String, String> settings) {
        try {
            return JSON.writeValueAsString(settings);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    List<Connection> list() {
        return jdbc.sql(VIEW + " order by c.name, c.id").query(CONNECTION).list();
    }

    Optional<Connection> find(UUID id) {
        return jdbc.sql(VIEW + " where c.id = ?").param(id).query(CONNECTION).optional();
    }

    Optional<UUID> lockByType(String type) {
        return jdbc.sql("select id from connection where type = ? for update").param(type).query(UUID.class)
                .optional();
    }

    Optional<UUID> lock(UUID id) {
        return jdbc.sql("select id from connection where id = ? for update").param(id).query(UUID.class).optional();
    }

    void insert(UUID tenantId, UUID id, String type, String name, boolean managed, Map<String, String> settings,
            Instant now) {
        jdbc.sql("""
                insert into connection (id, tenant_id, type, name, enabled, managed, settings, created_at, updated_at)
                values (?, ?, ?, ?, true, ?, cast(? as jsonb), ?, ?)
                """).params(id, tenantId, type, name, managed, json(settings), Timestamp.from(now),
                Timestamp.from(now)).update();
    }

    /** Connects again a system that was disconnected, with the settings given now. */
    void reconnect(UUID id, String name, Map<String, String> settings, Instant now) {
        jdbc.sql("""
                update connection set enabled = true, managed = true, name = ?, settings = cast(? as jsonb),
                                      sync_requested_at = null, updated_at = ?
                where id = ?
                """).params(name, json(settings), Timestamp.from(now), id).update();
    }

    boolean enabled(UUID id) {
        return jdbc.sql("select enabled from connection where id = ?").param(id).query(Boolean.class).single();
    }

    void disable(UUID id, Instant now) {
        jdbc.sql("update connection set enabled = false, updated_at = ? where id = ?")
                .params(Timestamp.from(now), id).update();
    }

    void requestSync(UUID id, Instant now) {
        jdbc.sql("update connection set sync_requested_at = ?, updated_at = ? where id = ?")
                .params(Timestamp.from(now), Timestamp.from(now), id).update();
    }

    /** False when the run was recorded before. */
    boolean insertRun(UUID tenantId, UUID connectionId, UUID runId, Instant started, Instant finished,
            boolean succeeded, int records, String error) {
        return jdbc.sql("""
                insert into connection_run (id, tenant_id, connection_id, started_at, finished_at, status, records,
                                            error)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do nothing
                """).params(runId, tenantId, connectionId, Timestamp.from(started), Timestamp.from(finished),
                succeeded ? "SUCCEEDED" : "FAILED", records, error).update() == 1;
    }

    List<ConnectionRun> runs(UUID connectionId, int limit) {
        return jdbc.sql("""
                select id, started_at, finished_at, status, records, error from connection_run
                where connection_id = ? order by finished_at desc, id limit ?
                """).params(connectionId, limit)
                .query((rs, n) -> new ConnectionRun(rs.getObject("id", UUID.class), at(rs, "started_at"),
                        at(rs, "finished_at"), rs.getString("status"), rs.getInt("records"), rs.getString("error")))
                .list();
    }
}
