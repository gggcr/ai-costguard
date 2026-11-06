package ai.costguard.platform.tenant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Scopes the current transaction to one tenant, so PostgreSQL RLS shows only the rows of that tenant. */
public class TenantSession {

    private final JdbcClient jdbc;

    public TenantSession(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Call inside a transaction: the setting ends with it, so a pooled connection never keeps a tenant. */
    public void bind(UUID tenantId) {
        jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenantId.toString())
                .query(String.class).single();
    }
}
