package ai.costguard.gateway.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One KRaft Kafka node, configured like infra/local/docker-compose.yml. The Testcontainers Kafka module advertises
 * 0.0.0.0 under Docker Desktop on Windows and Kafka refuses to start; a fixed port advertised as localhost works
 * everywhere.
 */
public final class KafkaNode extends GenericContainer<KafkaNode> {

    private final int port;

    public KafkaNode() {
        super("apache/kafka:3.9.0");
        port = freePort();
        addFixedExposedPort(port, 9092);
        withEnv("KAFKA_NODE_ID", "1");
        withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");
        withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + port);
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT");
        withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
        waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String getBootstrapServers() {
        return "localhost:" + port;
    }
}
