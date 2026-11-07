package ai.costguard.core.ledger;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/** Call inside a transaction already bound to the tenant. */
public interface SpendSummary {

    /** What agents spent per month, from the company budget's entries. */
    List<Month> agentSpendByMonth(YearMonth from, YearMonth to);

    record Month(YearMonth month, BigDecimal amount) {
    }
}
