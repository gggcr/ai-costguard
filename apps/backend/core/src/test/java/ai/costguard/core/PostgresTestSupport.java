package ai.costguard.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL shared by the tests; Docker is not required. The application connects as an ordinary
 * role because a superuser bypasses row-level security, and the isolation tests would prove nothing.
 */
public abstract class PostgresTestSupport {

    private static final String APP_ROLE = "costguard";
    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.start();
            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("CREATE DATABASE " + APP_ROLE + " OWNER " + APP_ROLE);
            }
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static DataSource adminDataSource() {
        return POSTGRES.getPostgresDatabase();
    }

    /** The application's database as the superuser: sees past RLS, for tests that check what is left. */
    protected static DataSource superuserAppDataSource() {
        return POSTGRES.getDatabase("postgres", APP_ROLE);
    }

    /** The application's database, for a connection as another role. */
    protected static String jdbcUrl() {
        return "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/" + APP_ROLE;
    }

    protected static DataSource appDataSource() {
        return POSTGRES.getDatabase(APP_ROLE, APP_ROLE);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl(APP_ROLE, APP_ROLE));
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> "");
        // Tests must not depend on the Bank of Russia being reachable.
        registry.add("costguard.catalog.cbr.enabled", () -> "false");
        // Kafka is checked by EventMappingTest and the wire format by AvroPublisherTest.
        registry.add("costguard.events.kafka.enabled", () -> "false");
    }
}
