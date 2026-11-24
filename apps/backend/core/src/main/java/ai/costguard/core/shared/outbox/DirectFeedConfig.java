package ai.costguard.core.shared.outbox;

import ai.costguard.events.wire.SingleObject;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Installs without Kafka ({@code EVENTS_TRANSPORT=direct}, ADR 0080): the outbox writes what the gateway reads to
 * {@code gateway_feed}, and the gateway pulls it from {@code /internal/v1/gateway/feed}. The other topics have no
 * reader there, so their events complete without being sent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "costguard.events.transport", havingValue = "direct")
class DirectFeedConfig {

    DirectFeedConfig(@Value("${costguard.events.kafka.enabled:true}") boolean kafka) {
        if (kafka) {
            throw new IllegalStateException("EVENTS_TRANSPORT=direct replaces Kafka: set CORE_EVENTS_KAFKA_ENABLED=false"
                    + " too, or the events would go to both.");
        }
    }

    @Bean
    GatewayFeed gatewayFeed(JdbcClient jdbc) {
        return new GatewayFeed(jdbc);
    }

    @Bean
    KafkaOutbox directOutbox(GatewayFeed feed) {
        return new KafkaOutbox(new AvroPublisher((topic, record) -> SingleObject.encode(record), feed::write));
    }

    /**
     * Behind the internal token (InternalSecurityConfig), never behind a user's. Component scanning finds this
     * class by itself, so it carries the condition of its own.
     */
    @Hidden
    @RestController
    @ConditionalOnProperty(name = "costguard.events.transport", havingValue = "direct")
    static class GatewayFeedController {

        static final int MAX_LIMIT = 1000;

        record Item(long seq, String topic, String key, byte[] value) {
        }

        record Page(UUID epoch, List<Item> items) {
        }

        private final GatewayFeed feed;

        GatewayFeedController(GatewayFeed feed) {
            this.feed = feed;
        }

        @GetMapping("/internal/v1/gateway/feed")
        Page feed(@RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "500") int limit) {
            return new Page(feed.epoch(), feed.after(after, Math.min(Math.max(limit, 1), MAX_LIMIT)).stream()
                    .map(e -> new Item(e.seq(), e.topic(), e.key(), e.value())).toList());
        }
    }
}
