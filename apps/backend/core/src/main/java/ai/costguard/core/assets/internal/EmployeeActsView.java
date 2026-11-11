package ai.costguard.core.assets.internal;

import ai.costguard.core.assets.EmployeeActs;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class EmployeeActsView implements EmployeeActs {

    private final JdbcClient jdbc;

    EmployeeActsView(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Act> actsOf(UUID employeeId) {
        return jdbc.sql("""
                select c.id, c.number, c.issued_on, a.model, a.inventory_number, g.return_due, g.returned_at
                from asset_act c
                join asset_assignment g on g.id = c.assignment_id
                join asset a on a.id = g.asset_id
                where g.employee_id = ?
                order by c.issued_on desc, c.number desc
                """).param(employeeId)
                .query((rs, i) -> new Act(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("issued_on", LocalDate.class), rs.getString("model"),
                        rs.getString("inventory_number"), rs.getObject("return_due", LocalDate.class),
                        rs.getObject("returned_at", LocalDate.class)))
                .list();
    }

    @Override
    public Optional<ActFile> file(UUID employeeId, UUID actId) {
        return jdbc.sql("""
                select c.number, c.pdf, c.sha256
                from asset_act c join asset_assignment g on g.id = c.assignment_id
                where c.id = ? and g.employee_id = ?
                """).params(actId, employeeId)
                .query((rs, i) -> new ActFile(rs.getString("number"), rs.getBytes("pdf"), rs.getString("sha256")))
                .optional();
    }
}
