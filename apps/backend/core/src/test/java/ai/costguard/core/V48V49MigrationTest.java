package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V48 (payroll_unmatched, finding R1) and V49 (the approver identity, finding H2) on a database that already holds
 * approvals, migrated by the real Flyway as the owner of the tables, as in production. Nothing here runs pieces of
 * the migrations by hand. All data is fictitious.
 */
class V48V49MigrationTest extends PostgresTestSupport {

    private static Flyway flyway(String db, String target) {
        return Flyway.configure().dataSource(database(db, "costguard")).locations("classpath:db/migration")
                .target(target).load();
    }

    private record Seeded(UUID tenant, UUID notarised, UUID plain, UUID pending) {
    }

    /** Three approvals as they were before V49: one sent to the notary (v1), one decided below the threshold, one open. */
    private static Seeded approvals(JdbcClient su) {
        UUID tenant = UUID.randomUUID();
        UUID notarised = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        su.sql("insert into tenant (id, name) values (?, 'ООО Миграция')").param(tenant).update();
        for (Object[] a : List.of(new Object[] {notarised, "approved", "fin@migr.example", "ab".repeat(32)},
                new Object[] {plain, "rejected", "fin@migr.example", null},
                new Object[] {pending, "pending", null, null})) {
            su.sql("""
                    insert into approval_request (id, tenant_id, operation_id, agent_id, description, amount, reason,
                                                  status, created_at, expires_at, decided_by, notary_hash)
                    values (?, ?, ?, ?, 'Операция', 1500, 'r', ?, now(), now() + interval '1 hour', ?, ?)""")
                    .params(a[0], tenant, UUID.randomUUID(), UUID.randomUUID(), a[1], a[2], a[3]).update();
        }
        return new Seeded(tenant, notarised, plain, pending);
    }

    private static Map<String, Object> approval(JdbcClient su, UUID id) {
        return su.sql("""
                select status, decided_by, notary_hash, decided_by_account, approver_hash, approver_hash_version
                from approval_request where id = ?""").param(id).query().singleRow();
    }

    private static boolean forced(JdbcClient su, String table) {
        return su.sql("""
                select relforcerowsecurity from pg_class where relname = ? and relnamespace = 'public'::regnamespace""")
                .param(table).query(Boolean.class).single();
    }

    @Test
    void v48AndV49RunOnExistingApprovalsWithoutRecomputingAnyHash() {
        String db = createDatabase("v4849");
        flyway(db, "47").migrate();
        JdbcClient su = JdbcClient.create(database(db, "postgres"));
        Seeded s = approvals(su);
        Map<String, Object> before = su.sql("select decided_by, notary_hash from approval_request where id = ?")
                .param(s.notarised()).query().singleRow();

        MigrateResult result = flyway(db, "49").migrate();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(su.sql("select version || ':' || success || ':' || installed_by from flyway_schema_history "
                + "where version in ('48', '49') order by installed_rank").query(String.class).list())
                .containsExactly("48:true:costguard", "49:true:costguard");

        // the approval already on the chain: v1, nothing recomputed, nothing invented
        assertThat(approval(su, s.notarised())).containsEntry("approver_hash_version", 1)
                .containsEntry("approver_hash", null).containsEntry("decided_by_account", null)
                .containsEntry("decided_by", before.get("decided_by"))
                .containsEntry("notary_hash", before.get("notary_hash"));
        // the others never went to the notary: no version
        assertThat(approval(su, s.plain())).containsEntry("approver_hash_version", null)
                .containsEntry("decided_by", "fin@migr.example");
        assertThat(approval(su, s.pending())).containsEntry("approver_hash_version", null)
                .containsEntry("status", "pending");

        for (String table : List.of("approval_request", "payroll_unmatched")) {
            assertThat(forced(su, table)).as("FORCE of %s", table).isTrue();
        }
        assertThat(su.sql("select count(*) from pg_policies where tablename = 'approval_request'")
                .query(Long.class).single()).as("tenant_isolation and backup_read").isEqualTo(2);
        // the stored hash must look like one, and a version is 1 or 2
        assertThatThrownBy(() -> su.sql("update approval_request set approver_hash = 'nope' where id = ?")
                .param(s.plain()).update()).hasMessageContaining("check");
        assertThatThrownBy(() -> su.sql("update approval_request set approver_hash_version = 3 where id = ?")
                .param(s.plain()).update()).hasMessageContaining("check");
        assertThatThrownBy(() -> su.sql("""
                insert into payroll_unmatched (id, tenant_id, fingerprint, source, kind, period, pay_date, amount,
                                               currency, reason, first_seen, last_seen)
                values (?, ?, 'f', 's', 'salary', date '2026-10-01', date '2026-10-10', 1, 'RUB', 'guess', now(), now())""")
                .params(UUID.randomUUID(), s.tenant()).update()).as("an unknown reason").hasMessageContaining("check");

        // Flyway again: nothing to do, and the checksums hold
        assertThat(flyway(db, "49").migrate().migrationsExecuted).isZero();
        flyway(db, "49").validate();
    }

    /**
     * V49 takes FORCE off approval_request for its update. A failure inside that update must leave the database as it
     * was before V49: Flyway runs each PostgreSQL migration in one transaction, and PostgreSQL rolls DDL back too.
     */
    @Test
    void anErrorInsideV49LeavesTheDatabaseAsBeforeWithForcedRls() {
        String db = createDatabase("v49err");
        flyway(db, "48").migrate();
        JdbcClient su = JdbcClient.create(database(db, "postgres"));
        Seeded s = approvals(su);
        su.sql("""
                create function v49_test_boom() returns trigger language plpgsql as $$
                begin raise exception 'simulated failure inside V49'; end $$""").update();
        su.sql("create trigger v49_test_boom before update on approval_request for each row "
                + "execute function v49_test_boom()").update();

        assertThatThrownBy(() -> flyway(db, "49").migrate()).isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("simulated failure inside V49");

        assertThat(forced(su, "approval_request")).as("FORCE after the failure").isTrue();
        assertThat(su.sql("""
                select count(*) from information_schema.columns where table_name = 'approval_request'
                  and column_name in ('decided_by_account', 'approver_hash', 'approver_hash_version')""")
                .query(Long.class).single()).as("no column of V49 is left").isZero();
        assertThat(su.sql("select max(version::int) from flyway_schema_history where success").query(Integer.class)
                .single()).isEqualTo(48);

        su.sql("drop trigger v49_test_boom on approval_request").update();
        assertThat(flyway(db, "49").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(approval(su, s.notarised())).containsEntry("approver_hash_version", 1);
    }
    /** The read-only preflight of the runbook (security-identity.md, section 4), run as it is on fictitious rows. */
    @Test
    void thePreflightFindsTheAddressesThatDifferInCaseOnly() throws Exception {
        String db = createDatabase("dupes");
        flyway(db, "49").migrate();
        JdbcClient su = JdbcClient.create(database(db, "postgres"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID t : List.of(a, b)) {
            su.sql("insert into tenant (id, name) values (?, 'ООО Дубли')").param(t).update();
        }
        UUID dept = UUID.randomUUID();
        UUID deptB = UUID.randomUUID();
        su.sql("insert into department (id, tenant_id, name) values (?, ?, 'Отдел'), (?, ?, 'Отдел')")
                .params(dept, a, deptB, b).update();
        String insert = """
                insert into employee (id, tenant_id, department_id, name, email, position, dismissed_at, erased_at)
                values (?, ?, ?, 'Тест', ?, 'x', cast(? as date), ?)""";
        su.sql(insert).params(UUID.randomUUID(), a, dept, "twin@dupes.example", null, null).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "Twin@dupes.example", null, null).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "left@dupes.example", "2026-01-31", null).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "LEFT@dupes.example", null, null).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "gone@dupes.example", null, null).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "Gone@dupes.example", null, java.sql.Timestamp.from(java.time.Instant.now())).update();
        su.sql(insert).params(UUID.randomUUID(), a, dept, "solo@dupes.example", null, null).update();
        su.sql(insert).params(UUID.randomUUID(), b, deptB, "twin@dupes.example", null, null).update();

        String preflight = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("repo.root"),
                "tools", "preflight", "employee-email-duplicates.sql"));
        List<Map<String, Object>> found = su.sql(preflight.replaceAll("(?m)^--.*$", "").strip().replaceAll(";$", ""))
                .query().listOfRows();
        assertThat(found).extracting(r -> r.get("tenant_id") + " " + r.get("address") + " " + r.get("employees")
                + " " + r.get("working")).containsExactly(a + " left@dupes.example 2 1", a + " twin@dupes.example 2 2");
        assertThat(su.sql("select count(*) from employee").query(Long.class).single()).as("read only").isEqualTo(8);
    }
}
