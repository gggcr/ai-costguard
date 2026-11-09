package ai.costguard.core.privacy.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ERASURE has RLS. COMPANY_ERASURE and PRIVACY_TENANT have none on purpose: the job lists tenants before it binds
 * one, and a company erasure outlives the company. Both hold ids, times and counts only.
 */
@Repository
class PrivacyRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERASURE_COLUMNS = "id, subject_id, reason, erased_at, report::text as report";

    private final JdbcClient jdbc;

    PrivacyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static String json(List<Step> steps) {
        try {
            return JSON.writeValueAsString(steps);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Step> steps(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<Step>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Erasure report is not a list of steps", e);
        }
    }

    /** For this transaction only: lets the retention job and a company erasure delete rows that are evidence. */
    void purgeMode() {
        jdbc.sql("select set_config('app.purge', 'on', true)").query(String.class).single();
    }

    // --- erasures of employees ---------------------------------------------------------------------------------

    private static final RowMapper<Erasure> ERASURE = (rs, n) -> new Erasure(rs.getObject("id", UUID.class),
            rs.getObject("subject_id", UUID.class), ErasureReason.valueOf(rs.getString("reason")),
            at(rs, "erased_at"), steps(rs.getString("report")));

    Optional<Erasure> erasureOf(UUID employeeId) {
        return jdbc.sql("select " + ERASURE_COLUMNS + " from erasure where subject_id = ?").param(employeeId)
                .query(ERASURE).optional();
    }

    Optional<Erasure> erasure(UUID id) {
        return jdbc.sql("select " + ERASURE_COLUMNS + " from erasure where id = ?").param(id).query(ERASURE)
                .optional();
    }

    List<Erasure> erasures(int limit) {
        return jdbc.sql("select " + ERASURE_COLUMNS + " from erasure order by erased_at desc, id limit ?")
                .param(limit).query(ERASURE).list();
    }

    void insertErasure(UUID tenantId, UUID id, UUID employeeId, ErasureReason reason, Instant at, List<Step> steps) {
        jdbc.sql("""
                insert into erasure (id, tenant_id, subject_id, reason, erased_at, report)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """).params(id, tenantId, employeeId, reason.name(), Timestamp.from(at), json(steps)).update();
    }

    int deleteErasures(UUID tenantId) {
        return jdbc.sql("delete from erasure where tenant_id = ?").param(tenantId).update();
    }

    // --- tenants and companies ---------------------------------------------------------------------------------

    List<UUID> tenants() {
        return jdbc.sql("select tenant_id from privacy_tenant order by tenant_id").query(UUID.class).list();
    }

    int forgetTenant(UUID tenantId) {
        return jdbc.sql("delete from privacy_tenant where tenant_id = ?").param(tenantId).update();
    }

    private static final RowMapper<CompanyErasure> COMPANY = (rs, n) -> {
        Instant cancelled = at(rs, "cancelled_at");
        Instant completed = at(rs, "completed_at");
        CompanyErasureStatus status = completed != null ? CompanyErasureStatus.completed
                : cancelled != null ? CompanyErasureStatus.cancelled : CompanyErasureStatus.scheduled;
        return new CompanyErasure(status, at(rs, "requested_at"), at(rs, "scheduled_for"), cancelled, completed,
                rs.getInt("attempts"), rs.getString("last_error"), steps(rs.getString("report")));
    };

    Optional<CompanyErasure> company(UUID tenantId) {
        return jdbc.sql("""
                select requested_at, scheduled_for, cancelled_at, completed_at, attempts, last_error,
                       report::text as report
                from company_erasure where tenant_id = ?
                """).param(tenantId).query(COMPANY).optional();
    }

    /** A request after a cancelled one starts the wait again; a completed erasure is never reopened. */
    void scheduleCompany(UUID tenantId, Instant requested, Instant due) {
        jdbc.sql("""
                insert into company_erasure (tenant_id, requested_at, scheduled_for) values (?, ?, ?)
                on conflict (tenant_id) do update set requested_at = excluded.requested_at,
                    scheduled_for = excluded.scheduled_for, cancelled_at = null, attempts = 0, last_error = null
                where company_erasure.completed_at is null
                """).params(tenantId, Timestamp.from(requested), Timestamp.from(due)).update();
    }

    boolean cancelCompany(UUID tenantId, Instant at) {
        return jdbc.sql("""
                update company_erasure set cancelled_at = ?
                where tenant_id = ? and completed_at is null and cancelled_at is null
                """).params(Timestamp.from(at), tenantId).update() == 1;
    }

    List<UUID> dueCompanies(Instant now) {
        return jdbc.sql("""
                select tenant_id from company_erasure
                where completed_at is null and cancelled_at is null and scheduled_for <= ?
                order by scheduled_for
                """).param(Timestamp.from(now)).query(UUID.class).list();
    }

    /** Locks the request, so two instances of the core never erase one company at the same time. */
    boolean lockDue(UUID tenantId, Instant now) {
        return jdbc.sql("""
                select tenant_id from company_erasure
                where tenant_id = ? and completed_at is null and cancelled_at is null and scheduled_for <= ?
                for update skip locked
                """).params(tenantId, Timestamp.from(now)).query(UUID.class).optional().isPresent();
    }

    void completeCompany(UUID tenantId, Instant at, List<Step> steps) {
        jdbc.sql("update company_erasure set completed_at = ?, last_error = null, report = cast(? as jsonb)"
                + " where tenant_id = ?").params(Timestamp.from(at), json(steps), tenantId).update();
    }

    /** Only the kind of failure is kept: an exception message may quote what it failed on. */
    void failCompany(UUID tenantId, String error) {
        jdbc.sql("update company_erasure set attempts = attempts + 1, last_error = ? where tenant_id = ?")
                .params(error, tenantId).update();
    }
}
