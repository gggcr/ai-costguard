package ai.costguard.core.org.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** How the identity provider knows each employee (scim_user, ADR 0043). RLS scopes the reads. */
@Repository
class ScimRepository {

    record Link(UUID employeeId, String externalId, String userName, boolean active, Instant createdAt,
            Instant updatedAt) {
    }

    private static final RowMapper<Link> LINK = (rs, n) -> new Link(rs.getObject("employee_id", UUID.class),
            rs.getString("external_id"), rs.getString("user_name"), rs.getBoolean("active"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    ScimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Map<UUID, Link> links() {
        Map<UUID, Link> out = new HashMap<>();
        jdbc.sql("select * from scim_user").query(LINK).list().forEach(l -> out.put(l.employeeId(), l));
        return out;
    }

    Optional<Link> link(UUID employeeId) {
        return jdbc.sql("select * from scim_user where employee_id = ?").param(employeeId).query(LINK).optional();
    }

    void save(UUID tenantId, UUID employeeId, String externalId, String userName, boolean active, Instant at) {
        jdbc.sql("""
                insert into scim_user (tenant_id, employee_id, external_id, user_name, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (employee_id) do update
                    set external_id = coalesce(excluded.external_id, scim_user.external_id),
                        user_name = excluded.user_name, active = excluded.active, updated_at = excluded.updated_at
                """).params(tenantId, employeeId, externalId, userName, active, Timestamp.from(at),
                Timestamp.from(at)).update();
    }
}
