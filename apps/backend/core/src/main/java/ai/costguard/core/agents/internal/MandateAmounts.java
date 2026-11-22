package ai.costguard.core.agents.internal;

import ai.costguard.core.org.AmountsInCurrency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The limits of a mandate are in the company's currency, the one the gateway prices operations in (ADR 0072). */
@Component
class MandateAmounts implements AmountsInCurrency {

    private final JdbcClient jdbc;

    MandateAmounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean holdsAmounts() {
        return jdbc.sql("select exists (select 1 from mandate)").query(Boolean.class).single();
    }
}
