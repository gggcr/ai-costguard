package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The demo data of {@code make demo} (tools/seed/seed.py) fits the schema of every migration: both synthetic
 * companies land whole, twice in a row. Skipped where Python is not installed.
 */
class DemoSeedTest extends PostgresTestSupport {

    private static final Path SEED = Path.of("../../../tools/seed/seed.py");

    private static String seedSql() throws IOException, InterruptedException {
        assumeTrue(Files.exists(SEED), "the seed script is outside this checkout");
        for (String python : List.of("python3", "python")) {
            Process process;
            try {
                process = new ProcessBuilder(python, "-I", SEED.toString()).redirectErrorStream(false).start();
            } catch (IOException notInstalled) {
                continue;
            }
            byte[] out = process.getInputStream().readAllBytes();
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return new String(out, StandardCharsets.UTF_8);
            }
        }
        assumeTrue(false, "Python is not installed");
        return "";
    }

    @Test
    void bothDemoCompaniesFitTheSchema() throws Exception {
        String sql = seedSql();
        String db = createDatabase("seed");
        Flyway.configure().dataSource(database(db, "costguard")).locations("classpath:db/migration").load().migrate();
        for (int run = 0; run < 2; run++) {
            try (Connection connection = database(db, "postgres").getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new AssertionError("the demo data does not fit the schema: " + e.getMessage(), e);
            }
        }

        JdbcClient su = JdbcClient.create(database(db, "postgres"));
        List<Map<String, Object>> tenants = su.sql("""
                select t.name, t.currency, t.country, t.language,
                       (select count(*) from employee e where e.tenant_id = t.id) as employees,
                       (select count(*) from subscription s where s.tenant_id = t.id) as subscriptions,
                       (select count(*) from license_assignment l where l.tenant_id = t.id) as licenses,
                       (select count(*) from user_account u where u.tenant_id = t.id) as accounts
                from tenant t order by t.name""").query().listOfRows();
        assertThat(tenants).hasSize(2);
        assertThat(tenants.get(0)).containsEntry("name", "Brightline Analytics Inc.").containsEntry("currency", "USD")
                .containsEntry("language", "en").containsEntry("employees", 5L).containsEntry("subscriptions", 4L)
                .containsEntry("licenses", 10L).containsEntry("accounts", 2L);
        assertThat(tenants.get(1)).containsEntry("currency", "RUB").containsEntry("language", null)
                .containsEntry("employees", 5L).containsEntry("licenses", 10L);
    }
}
