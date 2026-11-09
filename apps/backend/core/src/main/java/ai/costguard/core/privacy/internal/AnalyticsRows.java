package ai.costguard.core.privacy.internal;

import ai.costguard.core.shared.clickhouse.ClickHouse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A company's rows in ClickHouse, deleted when the company is erased (ADR 0021). The rows hold costs and agent
 * operations, no personal data, so an employee's erasure does not come here.
 */
@Component
class AnalyticsRows {

    private final ClickHouse clickhouse;
    private final List<String> tables;

    AnalyticsRows(ClickHouse clickhouse, PrivacyProperties properties) {
        this.clickhouse = clickhouse;
        this.tables = properties.clickhouseTables();
    }

    boolean configured() {
        return clickhouse.configured();
    }

    List<String> tables() {
        return tables;
    }

    /** A mutation per table, waited for, so the erasure is done when this returns. */
    void eraseTenant(UUID tenantId) {
        for (String table : tables) {
            if (!table.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Not a table name: " + table);
            }
            clickhouse.execute("ALTER TABLE " + table + " DELETE WHERE tenant_id = toUUID('" + tenantId + "')",
                    "erase " + table);
        }
    }
}
