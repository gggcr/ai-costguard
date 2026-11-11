package ai.costguard.core.cabinet.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VacationRepository {

    private static final String COLUMNS = """
            v.id, v.employee_id, e.name as employee_name, v.kind, v.starts_on, v.ends_on, v.days, v.comment,
            v.status, v.decided_by, v.decided_at, v.decision_comment, v.created_at""";

    private static final RowMapper<Vacation> ROW = (rs, i) -> {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Vacation(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getString("kind"), rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class), rs.getInt("days"), rs.getString("comment"),
                rs.getString("status"), rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
                rs.getString("decision_comment"), rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    VacationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, UUID employeeId, NewVacation v, int days, Instant at) {
        jdbc.sql("""
                insert into vacation_request (id, tenant_id, employee_id, kind, starts_on, ends_on, days, comment,
                                              status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)""")
                .params(id, tenantId, employeeId, v.kind().name(), v.startsOn(), v.endsOn(), days,
                        v.comment() == null || v.comment().isBlank() ? null : v.comment().strip(),
                        Timestamp.from(at))
                .update();
    }

    Optional<Vacation> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from vacation_request v join employee e on e.id = v.employee_id"
                + " where v.id = ?").param(id).query(ROW).optional();
    }

    /** For update: two decisions on one request do not both win. */
    Optional<Vacation> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from vacation_request v join employee e on e.id = v.employee_id"
                + " where v.id = ? for update of v").param(id).query(ROW).optional();
    }

    List<Vacation> of(UUID employeeId) {
        return jdbc.sql("select " + COLUMNS + " from vacation_request v join employee e on e.id = v.employee_id"
                + " where v.employee_id = ? order by v.starts_on desc, v.created_at desc").param(employeeId)
                .query(ROW).list();
    }

    List<Vacation> ofEmployees(Collection<UUID> employees) {
        if (employees.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + " from vacation_request v join employee e on e.id = v.employee_id"
                + " where v.employee_id in (:ids) order by (v.status = 'pending') desc, v.starts_on")
                .param("ids", employees).query(ROW).list();
    }

    List<Vacation> all(String status) {
        return jdbc.sql("select " + COLUMNS + " from vacation_request v join employee e on e.id = v.employee_id"
                + " where (cast(:status as text) is null or v.status = :status)"
                + " order by (v.status = 'pending') desc, v.starts_on").param("status", status).query(ROW).list();
    }

    /** Requests of the employee that hold days: pending or approved, not cancelled or rejected. */
    List<Vacation> holding(UUID employeeId) {
        return of(employeeId).stream().filter(v -> v.status().equals("pending") || v.status().equals("approved"))
                .toList();
    }

    void decide(UUID id, String status, String by, Instant at, String comment) {
        jdbc.sql("""
                update vacation_request set status = ?, decided_by = ?, decided_at = ?, decision_comment = ?
                where id = ?""")
                .params(status, by, Timestamp.from(at), comment == null || comment.isBlank() ? null : comment.strip(),
                        id)
                .update();
    }

    int countOf(UUID employeeId) {
        return jdbc.sql("select count(*) from vacation_request where employee_id = ?").param(employeeId)
                .query(Integer.class).single();
    }
}
