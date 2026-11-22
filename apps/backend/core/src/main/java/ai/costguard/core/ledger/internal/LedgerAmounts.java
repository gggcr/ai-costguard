package ai.costguard.core.ledger.internal;

import ai.costguard.core.org.AmountsInCurrency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Budget limits are in the company's currency (ADR 0072). */
@Component
class LedgerAmounts implements AmountsInCurrency {

    private final JdbcClient jdbc;

    LedgerAmounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean holdsAmounts() {
        return jdbc.sql("select exists (select 1 from budget)").query(Boolean.class).single();
    }
}
