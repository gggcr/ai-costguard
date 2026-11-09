package ai.costguard.core.privacy;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** How a module deletes its rows of a company: by tenant id, so tables without RLS are covered too. */
public final class TenantRows {

    private TenantRows() {
    }

    /** Deletes in the order given (children first) and reports each table. Table names are the module's own. */
    public static void delete(JdbcClient jdbc, UUID tenantId, Report report, String module, String... tables) {
        for (String table : tables) {
            if (!table.matches("[a-z_]+")) {
                throw new IllegalArgumentException("Not a table name: " + table);
            }
            int n = jdbc.sql("delete from " + table + " where tenant_id = ?").param(tenantId).update();
            report.deleted(module + "." + table, n);
        }
    }
}
