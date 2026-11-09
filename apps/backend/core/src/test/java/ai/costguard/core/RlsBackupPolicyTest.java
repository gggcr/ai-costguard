package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The backup role reads every company and writes nothing; the application role still sees only the tenant it bound
 * (ADR 0025). Every table with RLS must carry the backup policy, or the nightly dump would silently miss its rows.
 */
@SpringBootTest
class RlsBackupPolicyTest extends PostgresTestSupport {

    @Autowired JdbcClient app;

    private final JdbcClient superuser = JdbcClient.create(superuserAppDataSource());

    @Test
    void everyTableWithRlsHasTheBackupPolicy() {
        List<String> missing = superuser.sql("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity
                  and not exists (select 1 from pg_policies p where p.tablename = c.relname
                                  and p.policyname = 'backup_read' and p.cmd = 'SELECT')
                order by 1
                """).query(String.class).list();
        assertThat(missing).isEmpty();
    }

    @Test
    void theBackupRoleReadsEveryCompanyAndWritesNothing() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID t : List.of(a, b)) {
            superuser.sql("insert into notification (id, tenant_id, at, text, level) values (?, ?, now(), 't', 'info')")
                    .params(UUID.randomUUID(), t).update();
        }
        superuser.sql("""
                do $$ begin
                    if not exists (select 1 from pg_roles where rolname = 'costguard_backup') then
                        create role costguard_backup login nosuperuser nobypassrls password 'test-only';
                    end if;
                end $$""").update();
        String grants = Files.readString(Path.of(System.getProperty("repo.root"), "tools", "backup",
                "grant-backup-role.sql"));
        superuser.sql("set role costguard; " + grants.replaceAll("(?m)^--.*$", "") + " reset role;").update();

        JdbcClient backup = JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                jdbcUrl(), "costguard_backup", "test-only", true));
        assertThat(backup.sql("select count(distinct tenant_id) from notification where tenant_id in (?, ?)")
                .params(a, b).query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> backup.sql("delete from notification where tenant_id = ?").param(a).update())
                .hasMessageContaining("notification");
        assertThatThrownBy(() -> backup.sql(
                "insert into notification (id, tenant_id, at, text, level) values (gen_random_uuid(), ?, now(), 'x',"
                        + " 'info')").param(a).update()).hasMessageContaining("notification");
        // The application role, with no tenant bound, still sees nothing.
        assertThat(app.sql("select count(*) from notification where tenant_id in (?, ?)").params(a, b)
                .query(Long.class).single()).isZero();
    }
}
