package ai.costguard.gateway.events;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends every decision to the core: to {@code agent.operations.v1}, or without Kafka straight to the core
 * ({@code EVENTS_TRANSPORT=direct}, ADR 0080). If it does not answer, the message goes to a file on disk and is
 * sent later, so the agent is never held up and no operation is lost. Messages from the file keep their key, so the
 * core dedupes a message that was sent twice.
 */
public class OperationLog {

    static final String TOPIC = "agent.operations.v1";
    private static final Logger log = LoggerFactory.getLogger(OperationLog.class);

    /** Delivers one operation; the future fails when it was not delivered. May also throw before sending. */
    public interface Sender {
        CompletableFuture<?> send(String key, AgentOperation op);
    }

    private final Sender sender;
    private final Path buffer;
    private final Object lock = new Object();

    /** To Kafka, in the Schema Registry wire format. */
    public OperationLog(KafkaTemplate<String, byte[]> gatewayKafkaTemplate, SchemaRegistryClient registry,
            Path buffer) {
        this(gatewayKafkaTemplate == null ? null : (key, op) -> gatewayKafkaTemplate.send(TOPIC, key,
                AvroWire.encode(registry.idOf(TOPIC, op.getSchema()), op)), buffer);
    }

    public OperationLog(Sender sender, Path buffer) {
        this.sender = sender;
        this.buffer = buffer;
    }

    public void record(AgentOperation op) {
        String key = op.getAgentId().toString();
        try {
            sender.send(key, op).whenComplete((ok, error) -> {
                if (error != null) {
                    keep(key, op);
                }
            });
        } catch (RuntimeException e) {
            keep(key, op);
        }
    }

    /** The file holds Avro without the registry header, because the registry may be what is down. */
    private void keep(String key, AgentOperation op) {
        synchronized (lock) {
            try {
                Files.createDirectories(buffer.getParent());
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(buffer,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                    byte[] k = key.getBytes(StandardCharsets.UTF_8);
                    byte[] v = op.toByteBuffer().array();
                    out.writeInt(k.length);
                    out.write(k);
                    out.writeInt(v.length);
                    out.write(v);
                }
            } catch (IOException e) {
                log.error("Could not buffer operation {}; it is lost", op.getOperationId(), e);
            }
        }
    }

    /** Records in the buffer file; only for tests and diagnostics. */
    int replayCountForTest() {
        synchronized (lock) {
            if (!Files.exists(buffer)) {
                return 0;
            }
            int n = 0;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(buffer))) {
                while (true) {
                    try {
                        in.skipNBytes(in.readInt());
                        in.skipNBytes(in.readInt());
                        n++;
                    } catch (EOFException end) {
                        return n;
                    }
                }
            } catch (IOException e) {
                return n;
            }
        }
    }

    /** Sends the buffer in order. What fails stays in the file for the next attempt. */
    @Scheduled(fixedDelayString = "${costguard.gateway.replay-delay:PT10S}")
    public int replay() {
        synchronized (lock) {
            if (!Files.exists(buffer)) {
                return 0;
            }
            Path sending = buffer.resolveSibling(buffer.getFileName() + ".sending");
            try {
                Files.move(buffer, sending, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            int sent = 0;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(sending))) {
                while (true) {
                    String key;
                    AgentOperation op;
                    try {
                        byte[] k = new byte[in.readInt()];
                        in.readFully(k);
                        byte[] v = new byte[in.readInt()];
                        in.readFully(v);
                        key = new String(k, StandardCharsets.UTF_8);
                        op = AgentOperation.fromByteBuffer(java.nio.ByteBuffer.wrap(v));
                    } catch (EOFException end) {
                        break;
                    }
                    try {
                        sender.send(key, op).get(10, TimeUnit.SECONDS);
                        sent++;
                    } catch (Exception e) {
                        keep(key, op);
                    }
                }
            } catch (IOException e) {
                log.error("Buffer file is damaged; the rest of it is kept as {}", sending, e);
                return sent;
            }
            try {
                Files.deleteIfExists(sending);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return sent;
        }
    }
}
