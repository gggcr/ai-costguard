package ai.costguard.core.org.internal;

import ai.costguard.core.org.EmployeeDirectory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads carry no tenant filter: row-level security scopes them to the tenant bound to the transaction. */
@Repository
class OrgRepository {

    private static final String EMPLOYEE_COLUMNS = "id, name, email, department_id, position, dismissed_at, erased_at";
    private static final String USER_COLUMNS = "id, name, email, role, employee_id";

    private static final RowMapper<Company> COMPANY = (rs, n) -> new Company(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("currency"), rs.getString("plan"));

    private static final RowMapper<Department> DEPARTMENT = (rs, n) ->
            new Department(rs.getObject("id", UUID.class), rs.getString("name"));

    private static final RowMapper<Employee> EMPLOYEE = (rs, n) -> {
        LocalDate dismissedAt = rs.getObject("dismissed_at", LocalDate.class);
        return new Employee(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                rs.getObject("department_id", UUID.class), rs.getString("position"), dismissedAt == null, dismissedAt,
                rs.getTimestamp("erased_at") != null);
    };

    private static final RowMapper<UserAccount> USER = (rs, n) -> new UserAccount(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("email"), UserRole.valueOf(rs.getString("role")),
            rs.getObject("employee_id", UUID.class));

    private final JdbcClient jdbc;

    OrgRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Company> company() {
        return jdbc.sql("select id, name, currency, plan from tenant").query(COMPANY).optional();
    }

    void saveCompany(UUID tenantId, CompanySettings settings) {
        jdbc.sql("""
                insert into tenant (id, name, currency) values (?, ?, ?)
                on conflict (id) do update set name = excluded.name, currency = excluded.currency
                """).params(tenantId, settings.name(), settings.currency()).update();
    }

    List<Department> departments() {
        return jdbc.sql("select id, name from department order by name").query(DEPARTMENT).list();
    }

    boolean departmentExists(UUID id) {
        return jdbc.sql("select id from department where id = ?").param(id).query(UUID.class).optional().isPresent();
    }

    void insertDepartment(UUID tenantId, UUID id, String name) {
        jdbc.sql("insert into department (id, tenant_id, name) values (?, ?, ?)").params(id, tenantId, name).update();
    }

    List<Employee> employees(UUID departmentId) {
        return jdbc.sql("select " + EMPLOYEE_COLUMNS + """
                 from employee
                 where (cast(:department as uuid) is null or department_id = :department)
                 order by name, id
                """).param("department", departmentId).query(EMPLOYEE).list();
    }

    Optional<Employee> employee(UUID id) {
        return jdbc.sql("select " + EMPLOYEE_COLUMNS + " from employee where id = ?").param(id)
                .query(EMPLOYEE).optional();
    }

    /** The row lock makes two simultaneous dismissals publish one event. */
    Optional<Employee> employeeForUpdate(UUID id) {
        return jdbc.sql("select " + EMPLOYEE_COLUMNS + " from employee where id = ? for update").param(id)
                .query(EMPLOYEE).optional();
    }

    void insertEmployee(UUID tenantId, UUID id, EmployeeDetails e) {
        jdbc.sql("""
                insert into employee (id, tenant_id, department_id, name, email, position)
                values (?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, e.departmentId(), e.name(), e.email(), e.position()).update();
    }

    void updateEmployee(UUID id, EmployeeDetails e) {
        jdbc.sql("update employee set department_id = ?, name = ?, email = ?, position = ? where id = ?")
                .params(e.departmentId(), e.name(), e.email(), e.position(), id).update();
    }

    void dismiss(UUID id, LocalDate date) {
        jdbc.sql("update employee set dismissed_at = ? where id = ?").params(date, id).update();
    }

    List<UserAccount> users() {
        return jdbc.sql("select " + USER_COLUMNS + " from user_account order by name, id").query(USER).list();
    }

    Optional<UserAccount> user(UUID id) {
        return jdbc.sql("select " + USER_COLUMNS + " from user_account where id = ?").param(id)
                .query(USER).optional();
    }

    void insertUser(UUID tenantId, UUID id, NewUser u) {
        jdbc.sql("""
                insert into user_account (id, tenant_id, name, email, role, employee_id)
                values (?, ?, ?, ?, ?, ?)
                """).params(id, tenantId, u.name(), u.email(), u.role().name(), u.employeeId()).update();
    }

    Optional<UUID> employeeOfUser(String email) {
        return jdbc.sql("select employee_id from user_account where email = ? and employee_id is not null")
                .param(email).query(UUID.class).optional();
    }

    Optional<UUID> employeeByEmail(String email) {
        return jdbc.sql("select id from employee where lower(email) = ? order by dismissed_at nulls first limit 1").param(email)
                .query(UUID.class).optional();
    }

    java.util.Map<UUID, LocalDate> dismissed() {
        var out = new java.util.HashMap<UUID, LocalDate>();
        jdbc.sql("select id, dismissed_at from employee where dismissed_at is not null")
                .query((java.sql.ResultSet rs) -> {
                    out.put(rs.getObject("id", UUID.class), rs.getObject("dismissed_at", LocalDate.class));
                });
        return out;
    }

    Optional<EmployeeDirectory.EmployeeCard> card(UUID id) {
        return jdbc.sql("""
                select e.id, e.name, e.position, d.name as department, e.email, e.dismissed_at
                from employee e join department d on d.id = e.department_id
                where e.id = ?
                """).param(id)
                .query((rs, n) -> new EmployeeDirectory.EmployeeCard(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("position"), rs.getString("department"),
                        rs.getString("email"), rs.getObject("dismissed_at", LocalDate.class)))
                .optional();
    }

    record Named(UUID id, String name) {
    }

    List<Named> employeeNames() {
        return jdbc.sql("select id, name from employee")
                .query((rs, n) -> new Named(rs.getObject("id", UUID.class), rs.getString("name"))).list();
    }

    /** Locks every owner, so two simultaneous demotions cannot both see a second owner and leave none. */
    long lockOwners() {
        return jdbc.sql("select id from user_account where role = 'owner' for update").query(UUID.class).list().size();
    }

    void deleteUser(UUID id) {
        jdbc.sql("delete from user_account where id = ?").param(id).update();
    }

    void setRole(UUID id, UserRole role) {
        jdbc.sql("update user_account set role = ? where id = ?").params(role.name(), id).update();
    }

    Optional<UUID> userByEmail(String email) {
        return jdbc.sql("select id from user_account where lower(email) = lower(?)").param(email).query(UUID.class)
                .optional();
    }

    /** What the employee limit of the plan counts: people still working, not dismissed or erased (ADR 0033). */
    long countActiveEmployees() {
        return jdbc.sql("select count(*) from employee where dismissed_at is null and erased_at is null")
                .query(Long.class).single();
    }

    void setPlan(String plan) {
        jdbc.sql("update tenant set plan = ?").param(plan).update();
    }
}
