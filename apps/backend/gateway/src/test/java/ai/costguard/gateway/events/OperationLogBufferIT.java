package ai.costguard.gateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.OperationKind;
import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import ai.costguard.gateway.support.FakeSchemaRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ai.costguard.gateway.support.KafkaNode;

/** With Kafka away, operations go to disk; once it is back, they reach the topic in full. */
@Testcontainers
class OperationLogBufferIT {

    @Container
    static final KafkaNode KAFKA = new KafkaNode();

    @TempDir
    Path dir;

    private static KafkaTemplate<String, byte[]> template(String servers) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                "bootstrap.servers", servers, "key.serializer", StringSerializer.class,
                "value.serializer", ByteArraySerializer.class, "max.block.ms", 1500,
                "delivery.timeout.ms", 3000, "request.timeout.ms", 1000)));
    }

    private static AgentOperation op() {
        return AgentOperation.newBuilder().setOperationId(UUID.randomUUID()).setTenantId(UUID.randomUUID())
                .setAgentId(UUID.randomUUID()).setTaskId(null).setKind(OperationKind.PAID_API).setProvider("SPARK")
                .setModel(null).setDecision(OperationDecision.DENIED).setDenialCode("budget_exceeded")
                .setEstimatedCost(new BigDecimal("1.000000")).setActualCost(null).setCurrency("RUB")
                .setInputTokens(null).setOutputTokens(null).setMandateVersion(null).setPromptSimhash(null)
                .setOccurredAt(Instant.now()).build();
    }

    @Test
    void nothingIsLostWhileKafkaIsAway() throws Exception {
        try (FakeSchemaRegistry registryServer = new FakeSchemaRegistry()) {
            SchemaRegistryClient registry = new SchemaRegistryClient(registryServer.url());
            Path buffer = dir.resolve("operations.buf");
            List<AgentOperation> sent = List.of(op(), op(), op());

            OperationLog away = new OperationLog(template("127.0.0.1:1"), registry, buffer);
            sent.forEach(away::record);
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while ((!Files.exists(buffer) || away.replayCountForTest() < 3) && System.nanoTime() < until) {
                Thread.sleep(200);
            }
            assertThat(Files.exists(buffer)).isTrue();

            OperationLog back = new OperationLog(template(KAFKA.getBootstrapServers()), registry, buffer);
            assertThat(back.replay()).isEqualTo(3);
            assertThat(Files.exists(buffer)).isFalse();

            Properties p = new Properties();
            p.put("bootstrap.servers", KAFKA.getBootstrapServers());
            p.put("group.id", "t");
            p.put("auto.offset.reset", "earliest");
            p.put("key.deserializer", StringDeserializer.class);
            p.put("value.deserializer", ByteArrayDeserializer.class);
            List<UUID> received = new ArrayList<>();
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(OperationLog.TOPIC));
                long stop = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (received.size() < 3 && System.nanoTime() < stop) {
                    consumer.poll(Duration.ofMillis(300)).forEach(r -> received.add(
                            AvroWire.decode(r.value(), registry, AgentOperation.class).getOperationId()));
                }
            }
            assertThat(received).containsExactlyInAnyOrderElementsOf(sent.stream().map(AgentOperation::getOperationId).toList());
        }
    }
}
