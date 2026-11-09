package ai.costguard.core.activity.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class ActivityRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String json(Map<String, ?> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (Exception e) {
            throw new IllegalArgumentException("Details of an action must be plain values", e);
        }
    }

    private static Map<String, Object> details(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The actor of this Keycloak subject, with the name and e-mail of their latest token. */
    UUID actor(UUID tenantId, String subject, String name, String email) {
        return jdbc.sql("""
                insert into activity_actor (id, tenant_id, subject, name, email) values (?, ?, ?, ?, ?)
                on conflict (tenant_id, subject) do update set name = excluded.name, email = excluded.email
                returning id
                """).params(UUID.randomUUID(), tenantId, subject, name, email).query(UUID.class).single();
    }

    void insert(UUID tenantId, Instant at, UUID actorId, String system, String action, String targetType,
            UUID targetId, Map<String, ?> details) {
        jdbc.sql("""
                insert into activity_entry (id, tenant_id, at, actor_id, system, action, target_type, target_id,
                                            details)
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """).params(UUID.randomUUID(), tenantId, Timestamp.from(at), actorId, system, action, targetType,
                targetId, json(details)).update();
    }

    private static Instant at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /** Newest first. Every filter is optional. */
    List<ActivityEntry> entries(String action, UUID targetId, UUID actorId, Instant before, int limit) {
        return jdbc.sql("""
                select e.id, e.at, e.system, e.action, e.target_type, e.target_id, e.details::text as details,
                       a.id as actor_id, a.name, a.email, a.erased_at
                from activity_entry e left join activity_actor a on a.id = e.actor_id
                where (cast(:action as text) is null or e.action = :action)
                  and (cast(:target as uuid) is null or e.target_id = :target)
                  and (cast(:actor as uuid) is null or e.actor_id = :actor)
                  and (cast(:before as timestamptz) is null or e.at < :before)
                order by e.at desc, e.id desc
                limit :limit
                """).param("action", action).param("target", targetId).param("actor", actorId)
                .param("before", before == null ? null : Timestamp.from(before)).param("limit", limit)
                .query((rs, n) -> {
                    UUID actor = rs.getObject("actor_id", UUID.class);
                    return new ActivityEntry(rs.getObject("id", UUID.class), at(rs, "at"),
                            actor == null ? null : new ActivityActor(actor, rs.getString("name"),
                                    rs.getString("email"), rs.getTimestamp("erased_at") != null),
                            rs.getString("system"), rs.getString("action"), rs.getString("target_type"),
                            rs.getObject("target_id", UUID.class), details(rs.getString("details")));
                }).list();
    }

    /** Blanks the actor rows of the person; their entries stay and keep pointing at the blank row. */
    int eraseActors(Set<String> emails, Set<String> subjects, Instant at) {
        List<String> conditions = new ArrayList<>();
        if (!emails.isEmpty()) {
            conditions.add("lower(email) in (:emails)");
        }
        if (!subjects.isEmpty()) {
            conditions.add("subject in (:subjects)");
        }
        if (conditions.isEmpty()) {
            return 0;
        }
        var sql = jdbc.sql("update activity_actor set subject = null, name = null, email = null, erased_at = :at"
                + " where erased_at is null and (" + String.join(" or ", conditions) + ")")
                .param("at", Timestamp.from(at));
        if (!emails.isEmpty()) {
            sql = sql.param("emails", emails);
        }
        if (!subjects.isEmpty()) {
            sql = sql.param("subjects", subjects);
        }
        return sql.update();
    }

    int deleteBefore(Instant since) {
        return jdbc.sql("delete from activity_entry where at < ?").param(Timestamp.from(since)).update();
    }

    int deleteIdleActors() {
        return jdbc.sql("""
                delete from activity_actor a
                where not exists (select 1 from activity_entry e where e.actor_id = a.id)
                """).update();
    }
}
