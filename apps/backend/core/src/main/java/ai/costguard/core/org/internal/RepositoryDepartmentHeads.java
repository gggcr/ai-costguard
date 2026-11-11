package ai.costguard.core.org.internal;

import ai.costguard.core.org.DepartmentHeads;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class RepositoryDepartmentHeads implements DepartmentHeads {

    private final JdbcClient jdbc;

    RepositoryDepartmentHeads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> headOf(UUID employeeId) {
        return jdbc.sql("""
                select d.head_employee_id from employee e join department d on d.id = e.department_id
                join employee h on h.id = d.head_employee_id and h.dismissed_at is null
                where e.id = ? and d.head_employee_id <> e.id""")
                .param(employeeId).query(UUID.class).optional();
    }

    @Override
    public List<UUID> headedBy(UUID headEmployeeId) {
        return jdbc.sql("""
                select e.id from employee e join department d on d.id = e.department_id
                where d.head_employee_id = ? and e.id <> ? and e.dismissed_at is null""")
                .params(headEmployeeId, headEmployeeId).query(UUID.class).list();
    }
}
