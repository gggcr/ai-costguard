package ai.costguard.gateway.events;

import ai.costguard.events.wire.SingleObject;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The state topics without Kafka (ADR 0080): pulls what changed in the core's {@code gateway_feed} after the last
 * seq it applied, and applies it as {@link StateListeners} would apply the topics. The first pull starts at 0 and
 * brings the whole state, as a new consumer group reads a compacted topic from the start. A record that fails to
 * apply (Redis away, for example) is tried again on the next pull, and the ones after it wait: the order of one key
 * is kept. Until the first pull has caught up, the gateway knows no agent and refuses every operation. A new epoch
 * means the core's database was recreated; the feed is then read again from 0. What the old database had and the new
 * one does not stays in memory until the gateway restarts, as with a Kafka cluster that was replaced.
 */
public class CoreFeed {

    static final int PAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(CoreFeed.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    record Item(long seq, String topic, String key, byte[] value) {
    }

    record Page(UUID epoch, List<Item> items) {
    }

    private final HttpClient http;
    private final URI core;
    private final String token;
    private final StateApplier state;
    private volatile long after;
    private volatile UUID epoch;
    private volatile boolean caughtUp;
    private volatile String lastFailure;

    public CoreFeed(HttpClient http, URI core, String token, StateApplier state) {
        this.http = http;
        this.core = core;
        this.token = token;
        this.state = state;
    }

    @Scheduled(fixedDelayString = "${costguard.gateway.feed-delay:PT1S}")
    public void poll() {
        try {
            pull();
            lastFailure = null;
        } catch (IOException | RuntimeException e) {
            // once per kind of failure, not once a second while the core starts
            String failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!failure.equals(lastFailure)) {
                log.warn("Could not read the core's feed after seq {}: {}", after, failure);
                lastFailure = failure;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Applies everything after the last seq; returns how many records were applied. */
    public int pull() throws IOException, InterruptedException {
        int applied = 0;
        while (true) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                            core.resolve("/internal/v1/gateway/feed?after=" + after + "&limit=" + PAGE))
                    .header("X-Internal-Token", token).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("the core answered " + response.statusCode());
            }
            Page page = JSON.readValue(response.body(), Page.class);
            if (epoch != null && !epoch.equals(page.epoch())) {
                // the core's database was recreated: its seqs started again, so read it all from the start
                log.warn("The core's feed has a new epoch {} (was {}): reading it again from seq 0", page.epoch(),
                        epoch);
                epoch = page.epoch();
                after = 0;
                continue;
            }
            epoch = page.epoch();
            List<Item> items = page.items();
            for (Item item : items) {
                state.apply(item.topic(), item.key(), item.value(), type -> SingleObject.decode(item.value(), type));
                after = item.seq();
                applied++;
            }
            if (items.size() < PAGE) {
                break;
            }
        }
        if (!caughtUp) {
            caughtUp = true;
            log.info("Read the core's feed up to seq {}: the gateway has the state", after);
        }
        return applied;
    }

    public boolean caughtUp() {
        return caughtUp;
    }

    long after() {
        return after;
    }
}
