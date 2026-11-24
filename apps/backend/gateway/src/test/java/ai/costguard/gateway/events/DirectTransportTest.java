package ai.costguard.gateway.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.costguard.events.AgentOperation;
import ai.costguard.events.ExchangeRate;
import ai.costguard.events.OperationDecision;
import ai.costguard.events.OperationKind;
import ai.costguard.events.wire.SingleObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gateway without Kafka (ADR 0080) against a fake core: the feed is read in pages from the last seq, a record
 * that fails is read again, and operations the core does not take wait on disk until it does.
 */
class DirectTransportTest {

    static final String TOKEN = "test-internal-token-0123456789abcdef-0123";

    /** The core's side: the feed rows and what was posted, and whether it answers at all. */
    static final class FakeCore implements AutoCloseable {
        final List<Map<String, Object>> rows = new CopyOnWriteArrayList<>();
        final List<AgentOperation> posted = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<Long> asked = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile UUID epoch = UUID.randomUUID();
        final HttpServer server;

        FakeCore() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/gateway/feed", exchange -> {
                tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                Map<String, String> query = new LinkedHashMap<>();
                for (String p : exchange.getRequestURI().getQuery().split("&")) {
                    query.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
                }
                long after = Long.parseLong(query.get("after"));
                int limit = Integer.parseInt(query.get("limit"));
                asked.add(after);
                List<Map<String, Object>> page = rows.stream().filter(r -> (long) r.get("seq") > after).limit(limit)
                        .toList();
                byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("epoch", epoch.toString(), "items", page));
                exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
                if (status == 200) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.createContext("/internal/v1/gateway/operations", exchange -> {
                tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (status / 100 == 2) {
                    posted.add(SingleObject.decode(body, AgentOperation.class));
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    exchange.sendResponseHeaders(status, -1);
                }
                exchange.close();
            });
            server.start();
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void add(long seq, String topic, String key, SpecificRecord value) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", seq);
            row.put("topic", topic);
            row.put("key", key);
            row.put("value", value == null ? null : Base64.getEncoder().encodeToString(SingleObject.encode(value)));
            rows.add(row);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Records what it was given; fails on the keys a test names. */
    static final class RecordingState extends StateApplier {
        final List<String> applied = new ArrayList<>();
        final List<SpecificRecord> records = new ArrayList<>();
        volatile String failOn;

        RecordingState() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void apply(String topic, String key, byte[] value,
                Function<Class<? extends SpecificRecord>, SpecificRecord> decode) {
            if (key.equals(failOn)) {
                throw new IllegalStateException("Redis is away");
            }
            applied.add(topic + "/" + key);
            if (value != null) {
                records.add(decode.apply(TYPES.get(topic)));
            }
        }
    }

    private static ExchangeRate rate(String currency, String value) {
        return ExchangeRate.newBuilder().setCurrency(currency).setRoubles(new BigDecimal(value).setScale(8))
                .setRateDate(LocalDate.parse("2026-11-20")).build();
    }

    private static AgentOperation op() {
        return AgentOperation.newBuilder().setOperationId(UUID.randomUUID()).setTenantId(UUID.randomUUID())
                .setAgentId(UUID.randomUUID()).setTaskId(null).setKind(OperationKind.PAID_API).setProvider("Acme")
                .setModel(null).setDecision(OperationDecision.DENIED).setDenialCode("budget_exceeded")
                .setEstimatedCost(new BigDecimal("1.000000")).setActualCost(null).setCurrency("USD")
                .setInputTokens(null).setOutputTokens(null).setMandateVersion(null).setPromptSimhash(null)
                .setOccurredAt(Instant.now()).build();
    }

    FakeCore core;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        core = new FakeCore();
    }

    @AfterEach
    void stop() {
        core.close();
    }

    @Test
    void theFeedIsReadInPagesFromTheLastSeqAndDecoded() throws Exception {
        for (int i = 1; i <= CoreFeed.PAGE + 3; i++) {
            core.add(i * 2L, "catalog.rates.v1", "R" + i, rate("USD", "1." + i));
        }
        core.add(5000, "agents.state.v1", UUID.randomUUID().toString(), null);
        RecordingState state = new RecordingState();
        CoreFeed feed = new CoreFeed(http, core.url(), TOKEN, state);

        assertThat(feed.caughtUp()).isFalse();
        assertThat(feed.pull()).isEqualTo(CoreFeed.PAGE + 4);

        assertThat(feed.caughtUp()).isTrue();
        assertThat(feed.after()).isEqualTo(5000);
        assertThat(core.asked).containsExactly(0L, (long) CoreFeed.PAGE * 2);
        assertThat(core.tokens).containsOnly(TOKEN);
        assertThat(((ExchangeRate) state.records.get(0)).getRoubles()).isEqualByComparingTo("1.1");
        assertThat(state.applied).last().asString().startsWith("agents.state.v1/");

        core.add(5001, "catalog.rates.v1", "R1", rate("USD", "2"));
        assertThat(feed.pull()).isEqualTo(1);
        assertThat(core.asked).last().isEqualTo(5000L);
    }

    @Test
    void aRecordThatFailsIsReadAgainAndTheOnesAfterItWait() throws Exception {
        core.add(1, "catalog.rates.v1", "A", rate("USD", "1"));
        core.add(2, "catalog.rates.v1", "B", rate("GBP", "2"));
        core.add(3, "catalog.rates.v1", "C", rate("JPY", "3"));
        RecordingState state = new RecordingState();
        CoreFeed feed = new CoreFeed(http, core.url(), TOKEN, state);
        state.failOn = "B";

        feed.poll();

        assertThat(state.applied).containsExactly("catalog.rates.v1/A");
        assertThat(feed.caughtUp()).isFalse();
        state.failOn = null;
        feed.poll();
        assertThat(state.applied).containsExactly("catalog.rates.v1/A", "catalog.rates.v1/B", "catalog.rates.v1/C");
        assertThat(feed.caughtUp()).isTrue();
    }

    @Test
    void aRecreatedCoreDatabaseIsReadAgainFromTheStart() throws Exception {
        core.add(1, "catalog.rates.v1", "A", rate("USD", "1"));
        core.add(2, "catalog.rates.v1", "B", rate("GBP", "2"));
        RecordingState state = new RecordingState();
        CoreFeed feed = new CoreFeed(http, core.url(), TOKEN, state);
        feed.pull();

        // make down -v && make demo-lite: new database, new epoch, seqs from 1 again
        core.rows.clear();
        core.epoch = UUID.randomUUID();
        core.add(1, "catalog.rates.v1", "C", rate("JPY", "3"));

        assertThat(feed.pull()).isEqualTo(1);
        assertThat(core.asked).containsExactly(0L, 2L, 0L);
        assertThat(state.applied).last().isEqualTo("catalog.rates.v1/C");
        assertThat(feed.after()).isEqualTo(1);
    }

    @Test
    void aCoreThatRefusesLeavesTheStateAsItWas() throws Exception {
        core.add(1, "catalog.rates.v1", "A", rate("USD", "1"));
        core.status = 401;
        RecordingState state = new RecordingState();
        CoreFeed feed = new CoreFeed(http, core.url(), TOKEN, state);

        assertThatThrownBy(feed::pull).hasMessageContaining("401");
        feed.poll(); // logs, does not throw

        assertThat(state.applied).isEmpty();
        assertThat(feed.after()).isZero();
    }

    @Test
    void operationsWaitOnDiskWhileTheCoreIsAwayAndArriveOnceItIsBack() throws Exception {
        Path buffer = dir.resolve("operations.buf");
        DirectConfig config = new DirectConfig(core.url(), TOKEN);
        OperationLog log = config.operationLog(buffer);
        core.status = 503;
        List<AgentOperation> sent = List.of(op(), op(), op());

        sent.forEach(log::record);
        long until = System.nanoTime() + 10_000_000_000L;
        while (log.replayCountForTest() < 3 && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(log.replayCountForTest()).isEqualTo(3);
        assertThat(core.posted).isEmpty();

        core.status = 204;
        assertThat(log.replay()).isEqualTo(3);
        assertThat(Files.exists(buffer)).isFalse();
        // failed sends reach the file in the order they failed, as with Kafka; the core takes each one on its own
        assertThat(core.posted).extracting(AgentOperation::getOperationId)
                .containsExactlyInAnyOrderElementsOf(sent.stream().map(AgentOperation::getOperationId).toList());
        assertThat(core.tokens).containsOnly(TOKEN);
    }

    @Test
    void anOperationTheCoreTakesIsNotBuffered() throws Exception {
        Path buffer = dir.resolve("operations.buf");
        OperationLog log = new DirectConfig(core.url(), TOKEN).operationLog(buffer);
        AgentOperation op = op();

        log.record(op);
        long until = System.nanoTime() + 10_000_000_000L;
        while (core.posted.isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
        }

        assertThat(core.posted).extracting(AgentOperation::getOperationId).containsExactly(op.getOperationId());
        assertThat(Files.exists(buffer)).isFalse();
    }

    @Test
    void aShortTokenStopsTheStart() {
        assertThatThrownBy(() -> new DirectConfig(core.url(), "x".repeat(31))).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> new DirectConfig(core.url(), "")).hasMessageContaining("INTERNAL_TOKEN");
    }
}
