package ai.costguard.core.catalog.internal;

import ai.costguard.core.privacy.DataHolder;
import ai.costguard.core.privacy.Report;
import ai.costguard.core.privacy.TenantRows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Contract prices hold no personal data; exchange rates belong to no tenant (ADR 0021). */
@Component
class CatalogPersonalData implements DataHolder {

    private final JdbcClient jdbc;

    CatalogPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> tables() {
        return List.of("price");
    }

    @Override
    public void eraseCompany(UUID tenantId, Report report) {
        for (String sku : jdbc.sql("select distinct sku_id from price").query(String.class).list()) {
            report.tombstone("catalog.prices.v1", tenantId + ":" + sku);
        }
        TenantRows.delete(jdbc, tenantId, report, "catalog", "price");
    }
}
