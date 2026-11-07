package ai.costguard.core.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.events.AgentState;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A stand-in Schema Registry on a local port, so the real HTTP exchange is exercised. */
class AvroPublisherTest {

    private HttpServer registry;
    private final AtomicInteger registrations = new AtomicInteger();
    private volatile int answer = 200;

    @BeforeEach
    void start() throws IOException {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/subjects/", exchange -> {
            registrations.incrementAndGet();
            byte[] body = (answer == 200 ? "{\"id\":42}" : "{\"error_code\":409}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        registry.start();
    }

    @AfterEach
    void stop() {
        registry.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + registry.getAddress().getPort());
    }

    private static AgentStateChanged state() {
        return new AgentStateChanged(UUID.randomUUID(), UUID.randomUUID(), List.of("hash"), false, 3,
                "cost <= 1.0", new BigDecimal("300"), Instant.parse("2026-11-07T10:00:00Z"));
    }

    @Test
    void writesTheWireFormatAndRegistersOnce() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        AvroPublisher publisher = new AvroPublisher(new ai.costguard.events.wire.SchemaRegistryClient(url()), (topic, key, value) -> {
            keys.add(topic + "/" + key);
            sent.add(value);
        });
        AgentStateChanged event = state();
        EventMapping.Message message = EventMapping.of(event);

        publisher.publish(message.topic(), message.key(), message.record());
        publisher.publish(message.topic(), message.key(), message.record());

        assertThat(registrations).hasValue(1);
        assertThat(keys.getFirst()).isEqualTo("agents.state.v1/" + event.agentId());
        byte[] bytes = sent.getFirst();
        assertThat(bytes[0]).isZero();
        assertThat(ByteBuffer.wrap(bytes, 1, 4).getInt()).isEqualTo(42);
        AgentState decoded = new SpecificDatumReader<>(AgentState.class)
                .read(null, DecoderFactory.get().binaryDecoder(Arrays.copyOfRange(bytes, 5, bytes.length), null));
        assertThat(decoded.getAgentId()).isEqualTo(event.agentId());
        assertThat(decoded.getApprovalThreshold()).isEqualByComparingTo("300");
        assertThat(decoded.getMandateVersion()).isEqualTo(3);
    }

    @Test
    void anIncompatibleSchemaStopsThePublication() {
        answer = 409;
        List<byte[]> sent = new ArrayList<>();
        AvroPublisher publisher = new AvroPublisher(new ai.costguard.events.wire.SchemaRegistryClient(url()), (t, k, v) -> sent.add(v));
        EventMapping.Message message = EventMapping.of(state());

        assertThatThrownBy(() -> publisher.publish(message.topic(), message.key(), message.record()))
                .hasMessageContaining("refused");
        assertThat(sent).isEmpty();
    }
}
