package ai.costguard.core.findings.internal;

import ai.costguard.core.findings.FindingsSummary;
import org.springframework.stereotype.Component;

@Component
class RepositoryFindingsSummary implements FindingsSummary {

    private final FindingRepository findings;

    RepositoryFindingsSummary(FindingRepository findings) {
        this.findings = findings;
    }

    @Override
    public Totals totals() {
        FindingTotals t = findings.totals();
        return new Totals(t.open(), t.expectedSavings(), t.confirmedSavings());
    }
}
