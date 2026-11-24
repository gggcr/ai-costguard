package ai.costguard.gateway.events;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.events.wire.SchemaRegistryClient;
import ai.costguard.gateway.support.InMemoryGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** With EVENTS_TRANSPORT=direct the gateway starts with nothing of Kafka or the Schema Registry (ADR 0080). */
@TestPropertySource(properties = {"costguard.gateway.transport=direct",
        "costguard.gateway.internal-token=test-internal-token-0123456789abcdef-0123",
        "costguard.gateway.core-url=http://127.0.0.1:1", "costguard.gateway.feed-delay=PT1H"})
class DirectGatewayContextTest extends InMemoryGatewayTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theCoreFeedReplacesTheKafkaListeners() {
        assertThat(context.getBeansOfType(CoreFeed.class)).hasSize(1);
        assertThat(context.getBeansOfType(StateListeners.class)).isEmpty();
        assertThat(context.getBeansOfType(SchemaRegistryClient.class)).isEmpty();
        assertThat(context.containsBean("stateListenerFactory")).isFalse();
        assertThat(context.containsBean("gatewayKafkaTemplate")).isFalse();
    }
}
