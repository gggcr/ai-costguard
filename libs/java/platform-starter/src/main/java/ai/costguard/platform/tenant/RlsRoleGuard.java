package ai.costguard.platform.tenant;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stops the service from starting under a role that ignores row-level security. Such a role would show every
 * tenant's rows with no error at all, so this cannot be left to be noticed later.
 */
public class RlsRoleGuard implements InitializingBean {

    private final JdbcClient jdbc;

    public RlsRoleGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        boolean bypasses = jdbc.sql("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
                .query(Boolean.class).single();
        if (bypasses) {
            String role = jdbc.sql("select current_user").query(String.class).single();
            throw new IllegalStateException("Database role '" + role + "' bypasses row-level security, so tenants "
                    + "would see each other's data. Connect as an ordinary role: see "
                    + "infra/local/postgres/fix-app-role.sql");
        }
    }
}
