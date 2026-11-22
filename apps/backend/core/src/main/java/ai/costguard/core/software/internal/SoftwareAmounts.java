package ai.costguard.core.software.internal;

import ai.costguard.core.org.AmountsInCurrency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Prices of subscriptions, contracts and plans are in the company's currency (ADR 0072). */
@Component
class SoftwareAmounts implements AmountsInCurrency {

    private final JdbcClient jdbc;

    SoftwareAmounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean holdsAmounts() {
        return jdbc.sql("""
                select exists (select 1 from subscription) or exists (select 1 from contract)
                    or exists (select 1 from product_plan)""").query(Boolean.class).single();
    }
}
