package ai.costguard.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    /**
     * The Keycloak subject (the token's sub) of a test person: one per person and company, as Keycloak's ids are
     * unique in the realm. Tokens of tests use it, and {@link #login} links it to the person's account.
     */
    protected static String subject(UUID tenant, String email) {
        return "kc-" + tenant + "-" + email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The CostGuard account of a test person as an invitation or an onboarding leaves it: linked to the person's
     * Keycloak subject and, when given, to their employee (ADR 0051; the e-mail spoofing fix: a person is found by
     * the sub, not by the token's e-mail). Creates the account, or links one the company already has with this
     * e-mail. Writes as the superuser, past RLS, as the core would within the company.
     */
    protected static UUID login(UUID tenant, String email, String role, UUID employeeId) {
        return JdbcClient.create(superuserAppDataSource()).sql("""
                insert into user_account (id, tenant_id, name, email, role, employee_id, keycloak_subject)
                values (?, ?, coalesce((select e.name from employee e where e.id = ?), ?), ?, ?, ?, ?)
                on conflict (tenant_id, email) do update
                    set keycloak_subject = coalesce(user_account.keycloak_subject, excluded.keycloak_subject),
                        employee_id = coalesce(user_account.employee_id, excluded.employee_id)
                returning id
                """).params(UUID.randomUUID(), tenant, employeeId, email, email, role, employeeId, subject(tenant, email))
                .query(UUID.class).single();
    }

    /**
     * A database of its own on the same server, owned by the application role as the real one is, for a test that runs
     * Flyway itself (e.g. up to a version, then data, then the next migration). Returns its name.
     */
    protected static String createDatabase(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcClient.create(adminDataSource()).sql("create database " + name + " owner " + APP_ROLE).update();
        return name;
    }

    /** That database as this role: {@code costguard} (the owner, as Flyway connects) or {@code postgres}. */
    protected static DataSource database(String name, String role) {
        return POSTGRES.getDatabase(role, name);
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
