package ai.costguard.core.policies.internal;

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
class PolicyRepository {

    private static final RowMapper<PolicyRule> RULE = (rs, n) -> new PolicyRule(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("expression"), Effect.valueOf(rs.getString("effect")),
            rs.getBoolean("enabled"), rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    PolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<PolicyRule> rules() {
        return jdbc.sql("select id, name, expression, effect, enabled, updated_at from policy_rule order by name, id")
                .query(RULE).list();
    }

    Optional<PolicyRule> rule(UUID id) {
        return jdbc.sql("select id, name, expression, effect, enabled, updated_at from policy_rule where id = ?")
                .param(id).query(RULE).optional();
    }

    void insert(UUID tenantId, UUID id, RuleDetails r, Instant now) {
        jdbc.sql("""
                insert into policy_rule (id, tenant_id, name, expression, effect, enabled, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, r.name(), r.expression(), r.effect().name(), r.enabled(), Timestamp.from(now))
                .update();
    }

    boolean update(UUID id, RuleDetails r, Instant now) {
        return jdbc.sql("update policy_rule set name = ?, expression = ?, effect = ?, enabled = ?, updated_at = ? where id = ?")
                .params(r.name(), r.expression(), r.effect().name(), r.enabled(), Timestamp.from(now), id)
                .update() == 1;
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from policy_rule where id = ?").param(id).update() == 1;
    }

    Optional<Publication> publication() {
        return jdbc.sql("select version, published_at from policy_publication")
                .query((rs, n) -> new Publication(rs.getLong("version"), rs.getTimestamp("published_at").toInstant()))
                .optional();
    }

    /** One row per tenant; the upsert serializes two simultaneous publications. */
    long nextVersion(UUID tenantId, Instant now) {
        return jdbc.sql("""
                insert into policy_publication (tenant_id, version, published_at) values (?, 1, ?)
                on conflict (tenant_id) do update set version = policy_publication.version + 1,
                                                      published_at = excluded.published_at
                returning version
                """).params(tenantId, Timestamp.from(now)).query(Long.class).single();
    }
}
