package ai.costguard.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.costguard.gateway.ladder.CheckStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ladder order is part of the design: risk runs before the reserve, approval comes last. */
class CheckLadderOrderTest {

    @Test
    void stepsRunInTheOrderOfTheCheckLadder() {
        List<CheckStep> ladder = List.of(
                new IdentityFilter(),
                new MandateFilter(),
                new LoopFilter(),
                new RiskFilter(),
                new BudgetFilter(),
                new TaskFitFilter(),
                new ApprovalFilter());

        for (int i = 0; i < ladder.size(); i++) {
            assertEquals((i + 1) * 100, ladder.get(i).getOrder(), ladder.get(i).getClass().getSimpleName());
        }
    }
}
