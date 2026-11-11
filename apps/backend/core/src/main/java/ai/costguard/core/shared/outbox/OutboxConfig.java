package ai.costguard.core.shared.outbox;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Off with {@code costguard.events.kafka.enabled=false}: events then stay inside the core. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "costguard.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
class OutboxConfig {

    @Bean
    KafkaTemplate<String, byte[]> outboxKafkaTemplate(KafkaProperties properties) {
        var config = properties.buildProducerProperties();
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", ByteArraySerializer.class);
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        var template = new KafkaTemplate<String, byte[]>(new DefaultKafkaProducerFactory<>(config));
        // a producer span and the W3C traceparent header: the Python services continue the trace (ADR 0050)
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    AvroPublisher avroPublisher(KafkaTemplate<String, byte[]> outboxKafkaTemplate,
            @Value("${costguard.events.schema-registry-url:http://localhost:8085}") URI registry) {
        return new AvroPublisher(new ai.costguard.events.wire.SchemaRegistryClient(registry), (topic, key, value) -> {
            try {
                outboxKafkaTemplate.send(topic, key, value).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("Kafka did not take the message for " + topic, e);
            }
        });
    }

    @Bean
    KafkaOutbox kafkaOutbox(AvroPublisher avroPublisher) {
        return new KafkaOutbox(avroPublisher);
    }
}
