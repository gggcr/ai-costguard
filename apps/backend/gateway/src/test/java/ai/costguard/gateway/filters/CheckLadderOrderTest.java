package ai.costguard.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/** The ladder order is part of the design: risk runs before the reserve, approval comes last. */
class CheckLadderOrderTest {

    @Test
    void filtersRunInTheOrderOfTheCheckLadder() {
        List<Ordered> ladder = List.of(
                new IdentityFilter(),
                new MandateFilter(),
                new LoopFilter(),
                new RiskFilter(),
                new BudgetFilter(),
                new TaskFitFilter(),
                new ApprovalFilter());

        for (int i = 0; i < ladder.size(); i++) {
            assertEquals(i + 1, ladder.get(i).getOrder(), ladder.get(i).getClass().getSimpleName());
        }
    }
}
