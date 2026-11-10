package ai.costguard.core.agents.internal;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
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
class AgentRepository {

    private static final String AGENT_COLUMNS = """
            id, name, description, framework, department_id, owner_id, status, risk_score, risk_reason, mandate_id,
            created_at
            """;
    private static final String MANDATE_COLUMNS = """
            id, name, models, vendors, max_per_operation, approval_threshold, hours, status, version, updated_at,
            (select n.mandate_hash from mandate_notarization n
              where n.mandate_id = mandate.id and n.version = mandate.version) as mandate_hash,
            (select n.near_tx_hash from mandate_notarization n
              where n.mandate_id = mandate.id and n.version = mandate.version) as near_tx_hash
            """;

    private static final RowMapper<Agent> AGENT = (rs, n) -> new Agent(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("description"), rs.getString("framework"),
            rs.getObject("department_id", UUID.class), rs.getObject("owner_id", UUID.class),
            AgentStatus.valueOf(rs.getString("status")), rs.getInt("risk_score"), rs.getString("risk_reason"),
            rs.getObject("mandate_id", UUID.class), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<VirtualKey> KEY = (rs, n) -> new VirtualKey(rs.getObject("id", UUID.class),
            rs.getObject("agent_id", UUID.class), rs.getString("prefix"), rs.getTimestamp("created_at").toInstant(),
            KeyStatus.valueOf(rs.getString("status")));

    private static final RowMapper<Mandate> MANDATE = (rs, n) -> new Mandate(rs.getObject("id", UUID.class),
            rs.getString("name"), strings(rs.getArray("models")), strings(rs.getArray("vendors")),
            rs.getBigDecimal("max_per_operation").stripTrailingZeros(),
            rs.getBigDecimal("approval_threshold").stripTrailingZeros(), rs.getString("hours"),
            MandateStatus.valueOf(rs.getString("status")), rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant(), rs.getString("mandate_hash"), rs.getString("near_tx_hash"));

    private final JdbcClient jdbc;

    AgentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    List<Agent> agents() {
        return jdbc.sql("select " + AGENT_COLUMNS + " from agent order by name, id").query(AGENT).list();
    }

    Optional<Agent> agent(UUID id) {
        return jdbc.sql("select " + AGENT_COLUMNS + " from agent where id = ?").param(id).query(AGENT).optional();
    }

    /** Serializes changes of one agent, so state events leave in the order the changes happened. */
    Optional<Agent> agentForUpdate(UUID id) {
        return jdbc.sql("select " + AGENT_COLUMNS + " from agent where id = ? for update").param(id)
                .query(AGENT).optional();
    }

    List<Agent> agentsOfMandate(UUID mandateId) {
        return jdbc.sql("select " + AGENT_COLUMNS + " from agent where mandate_id = ? order by id for update")
                .param(mandateId).query(AGENT).list();
    }

    void insertAgent(UUID tenantId, UUID id, AgentDetails a, Instant now) {
        jdbc.sql("""
                insert into agent (id, tenant_id, name, description, framework, department_id, owner_id, mandate_id,
                                   status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)
                """).params(id, tenantId, a.name(), a.description(), a.framework(), a.departmentId(), a.ownerId(),
                a.mandateId(), at(now)).update();
    }

    void updateAgent(UUID id, AgentDetails a) {
        jdbc.sql("""
                update agent set name = ?, description = ?, framework = ?, department_id = ?, owner_id = ?,
                                 mandate_id = ?
                where id = ?
                """).params(a.name(), a.description(), a.framework(), a.departmentId(), a.ownerId(), a.mandateId(), id)
                .update();
    }

    void setStatus(UUID id, AgentStatus status) {
        jdbc.sql("update agent set status = ? where id = ?").params(status.name(), id).update();
    }

    void deleteAgent(UUID id) {
        jdbc.sql("delete from agent where id = ?").param(id).update();
    }

    List<VirtualKey> keys(UUID agentId) {
        return jdbc.sql("""
                select id, agent_id, prefix, created_at, status from virtual_key
                where agent_id = ? order by created_at desc, id
                """).param(agentId).query(KEY).list();
    }

    Optional<VirtualKey> key(UUID agentId, UUID keyId) {
        return jdbc.sql("select id, agent_id, prefix, created_at, status from virtual_key where id = ? and agent_id = ?")
                .params(keyId, agentId).query(KEY).optional();
    }

    List<String> activeKeyHashes(UUID agentId) {
        return jdbc.sql("select hash from virtual_key where agent_id = ? and status = 'active' order by hash")
                .param(agentId).query(String.class).list();
    }

    void insertKey(UUID tenantId, VirtualKey key, String hash) {
        jdbc.sql("""
                insert into virtual_key (id, tenant_id, agent_id, prefix, hash, status, created_at)
                values (?, ?, ?, ?, ?, 'active', ?)
                """).params(key.id(), tenantId, key.agentId(), key.prefix(), hash, at(key.createdAt())).update();
    }

    void revokeKey(UUID keyId) {
        jdbc.sql("update virtual_key set status = 'revoked' where id = ?").param(keyId).update();
    }

    List<Mandate> mandates() {
        return jdbc.sql("select " + MANDATE_COLUMNS + " from mandate order by name, id").query(MANDATE).list();
    }

    Optional<Mandate> mandate(UUID id) {
        return jdbc.sql("select " + MANDATE_COLUMNS + " from mandate where id = ?").param(id)
                .query(MANDATE).optional();
    }

    Optional<Mandate> mandateForUpdate(UUID id) {
        return jdbc.sql("select " + MANDATE_COLUMNS + " from mandate where id = ? for update").param(id)
                .query(MANDATE).optional();
    }

    void insertMandate(UUID tenantId, UUID id, MandateDetails m, Instant now) {
        jdbc.sql("""
                insert into mandate (id, tenant_id, name, models, vendors, max_per_operation, approval_threshold,
                                     hours, status, updated_at)
                values (:id, :tenant, :name, :models::text[], :vendors::text[], :max, :threshold, :hours, 'draft', :now)
                """)
                .param("id", id).param("tenant", tenantId).param("name", m.name())
                .param("models", literal(m.models())).param("vendors", literal(m.vendors()))
                .param("max", m.maxPerOperation()).param("threshold", m.approvalThreshold())
                .param("hours", m.hours()).param("now", at(now)).update();
    }

    /** An edit is a draft again; agents keep the last published terms until it is published. */
    void updateMandate(UUID id, MandateDetails m, Instant now) {
        jdbc.sql("""
                update mandate set name = :name, models = :models::text[], vendors = :vendors::text[],
                       max_per_operation = :max, approval_threshold = :threshold, hours = :hours,
                       status = 'draft', updated_at = :now
                where id = :id
                """)
                .param("id", id).param("name", m.name())
                .param("models", literal(m.models())).param("vendors", literal(m.vendors()))
                .param("max", m.maxPerOperation()).param("threshold", m.approvalThreshold())
                .param("hours", m.hours()).param("now", at(now)).update();
    }

    void publishMandate(UUID id, String expression, BigDecimal threshold, Instant now) {
        jdbc.sql("""
                update mandate set status = 'published', version = version + 1, published_expression = ?,
                                   published_threshold = ?, updated_at = ?
                where id = ?
                """).params(expression, threshold, at(now), id).update();
    }

    Optional<PublishedTerms> publishedTerms(UUID mandateId) {
        return jdbc.sql("""
                select version, published_expression, published_threshold from mandate
                where id = ? and published_expression is not null
                """).param(mandateId)
                .query((rs, n) -> new PublishedTerms(rs.getInt("version"), rs.getString("published_expression"),
                        rs.getBigDecimal("published_threshold")))
                .optional();
    }

    /** The hash of a version just published, waiting for the notary. Publishing the version twice keeps the first. */
    void insertNotarization(UUID tenantId, UUID mandateId, int version, String hash, Instant now) {
        jdbc.sql("""
                insert into mandate_notarization (tenant_id, mandate_id, version, mandate_hash, requested_at)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """).params(tenantId, mandateId, version, hash, at(now)).update();
    }

    Optional<String> notarizationHash(UUID mandateId, int version) {
        return jdbc.sql("select mandate_hash from mandate_notarization where mandate_id = ? and version = ?")
                .params(mandateId, version).query(String.class).optional();
    }

    /** The first transaction reported wins: a repeat changes nothing. */
    void notarized(UUID mandateId, int version, String txHash, Instant at) {
        jdbc.sql("""
                update mandate_notarization set near_tx_hash = ?, notarized_at = ?
                where mandate_id = ? and version = ? and near_tx_hash is null
                """).params(txHash, at(at), mandateId, version).update();
    }

    boolean mandateInUse(UUID id) {
        return jdbc.sql("select exists (select from agent where mandate_id = ?)").param(id)
                .query(Boolean.class).single();
    }

    void deleteMandate(UUID id) {
        jdbc.sql("delete from mandate where id = ?").param(id).update();
    }

    /** A PostgreSQL array literal: every element is quoted, so commas and braces in a name stay inside it. */
    private static String literal(List<String> values) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            String escaped = values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
            out.append(i == 0 ? "" : ",").append('"').append(escaped).append('"');
        }
        return out.append('}').toString();
    }

    /** What the agent limit of the plan counts: every agent, suspended ones too (ADR 0037). */
    long count() {
        return jdbc.sql("select count(*) from agent").query(Long.class).single();
    }
}
