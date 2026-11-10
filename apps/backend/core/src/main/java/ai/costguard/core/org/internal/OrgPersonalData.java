package ai.costguard.core.org.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.ErasureSubjects;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.Retention;
import ai.costguard.core.privacy.Subject;
import ai.costguard.core.privacy.TenantRows;
import ai.costguard.core.shared.names.Names;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who a person is, for the privacy module, and the org part of erasing them (ADR 0021). It runs after every other
 * module: they still find the person by the e-mail this one blanks.
 *
 * <p>An erased employee keeps the row and its id, so assignments, agents and findings that point at it stay whole.
 * The row then names nobody. The person's CostGuard users are deleted: a user is an account, not history.
 */
@Component
class OrgPersonalData implements DataHolder, ErasureSubjects {

    static final String ERASED_NAME = "Удалённый сотрудник";

    private final JdbcClient jdbc;
    private final Clock clock;

    OrgPersonalData(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public List<String> tables() {
        return List.of("tenant", "department", "employee", "user_account", "registration", "registration_timer",
                "invitation");
    }

    private record Row(UUID id, String name, String email, LocalDate dismissedAt, boolean erased) {
    }

    @Override
    public Optional<Person> person(UUID employeeId) {
        Optional<Row> row = jdbc.sql("select id, name, email, dismissed_at, erased_at from employee where id = ?")
                .param(employeeId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getObject("dismissed_at", LocalDate.class),
                        rs.getTimestamp("erased_at") != null))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row e = row.get();
        Set<String> emails = new TreeSet<>();
        emails.add(e.email().strip().toLowerCase(Locale.ROOT));
        record User(String email, String role) {
        }
        List<User> users = jdbc.sql("select email, role from user_account where employee_id = ? or lower(email) = ?")
                .params(employeeId, e.email().strip().toLowerCase(Locale.ROOT))
                .query((rs, n) -> new User(rs.getString("email"), rs.getString("role"))).list();
        users.forEach(u -> emails.add(u.email().strip().toLowerCase(Locale.ROOT)));
        long owners = jdbc.sql("select count(*) from user_account where role = 'owner'").query(Long.class).single();
        long theirs = users.stream().filter(u -> "owner".equals(u.role())).count();
        return Optional.of(new Person(e.id(), emails, Names.clean(e.name()), e.dismissedAt(), e.erased(),
                theirs > 0 && theirs >= owners));
    }

    @Override
    public List<UUID> dismissedBefore(LocalDate day) {
        return jdbc.sql("select id from employee where dismissed_at < ? and erased_at is null order by dismissed_at")
                .param(day).query(UUID.class).list();
    }

    @Override
    public Optional<String> companyName() {
        return jdbc.sql("select name from tenant").query(String.class).optional();
    }

    @Override
    public void eraseEmployee(Subject subject, Retention retention, Report report) {
        var users = jdbc.sql("delete from user_account where employee_id = :id or lower(email) in (:emails)")
                .param("id", subject.employeeId()).param("emails", subject.emails()).update();
        report.deleted("org.user_account", users);
        int employees = jdbc.sql("""
                update employee set name = ?, email = ?, position = '', erased_at = ?
                where id = ? and erased_at is null
                """).params(ERASED_NAME, "erased-" + subject.employeeId() + "@erased.invalid",
                Timestamp.from(clock.instant()), subject.employeeId()).update();
        report.anonymized("org.employee", employees);
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        TenantRows.delete(jdbc, tenantId, report, "org", "invitation", "registration", "registration_timer",
                "user_account", "employee", "department");
        report.deleted("org.tenant", jdbc.sql("delete from tenant where id = ?").param(tenantId).update());
    }
}
