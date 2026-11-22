package ai.costguard.core.assets.internal;

import ai.costguard.core.org.AmountsInCurrency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What equipment cost is in the company's currency, as accounting knows it (ADR 0072). */
@Component
class AssetAmounts implements AmountsInCurrency {

    private final JdbcClient jdbc;

    AssetAmounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean holdsAmounts() {
        return jdbc.sql("select exists (select 1 from asset where cost > 0)").query(Boolean.class).single();
    }
}
