package ai.costguard.core.ledger.internal;

import ai.costguard.core.ledger.SpendSummary;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class RepositorySpendSummary implements SpendSummary {

    private final LedgerRepository ledger;

    RepositorySpendSummary(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    public List<Month> agentSpendByMonth(YearMonth from, YearMonth to) {
        return ledger.companySpendByMonth(from.atDay(1), to.plusMonths(1).atDay(1));
    }
}
