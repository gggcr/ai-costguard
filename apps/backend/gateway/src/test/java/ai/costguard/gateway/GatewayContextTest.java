package ai.costguard.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.costguard.gateway.ladder.CheckLadder;
import ai.costguard.gateway.ladder.OperationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The gateway context starts with all seven check steps, and an unfinished ladder never allows spending.
 * Redis and Kafka containers come back with the budget reserve and event publishing tests.
 */
@SpringBootTest
class GatewayContextTest {



    @Autowired
    CheckLadder ladder;

    @Test
    void unfinishedLadderNeverAllowsSpending() {
        OperationContext op = new OperationContext("op-1", null, null, "model_call", null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> ladder.run(op).block());
    }
}