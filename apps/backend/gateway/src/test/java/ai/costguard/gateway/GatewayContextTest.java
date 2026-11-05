package ai.costguard.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.costguard.gateway.ladder.CheckLadder;
import ai.costguard.gateway.ladder.OperationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The gateway starts on real Redis and Kafka, and an unfinished ladder never allows spending. */
@SpringBootTest
@Testcontainers
class GatewayContextTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.0");

    @Autowired
    CheckLadder ladder;

    @Test
    void unfinishedLadderNeverAllowsSpending() {
        OperationContext op = new OperationContext("op-1", null, null, "model_call", null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> ladder.run(op).block());
    }
}
