package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * make demo-lite runs the core without Kafka (ADR 0064) and sets spring.kafka.listener.auto-startup=false; the
 * inbox's own factory ignored it, so a listener started anyway and the core stopped at start (found 2026-11-14).
 */
class InboxListenerStartupTest {

    @Test
    void theInboxFactoryFollowsTheAutoStartupSetting() {
        InboxConfig config = new InboxConfig();
        assertThat(ReflectionTestUtils.getField(config.inboxListenerFactory(new KafkaProperties(), false),
                "autoStartup")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(config.inboxListenerFactory(new KafkaProperties(), true),
                "autoStartup")).isEqualTo(true);
    }
}
