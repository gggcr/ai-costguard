package ai.costguard.gateway.events;

import ai.costguard.events.wire.SchemaRegistryClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * How state reaches the gateway and operations leave it: over Kafka (the default), or straight with the core when
 * {@code costguard.gateway.transport=direct} (ADR 0080, {@link DirectConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class EventsConfig {

    static final String BUFFER = "${costguard.gateway.buffer-file:${java.io.tmpdir}/costguard-gateway/operations.buf}";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @ConditionalOnProperty(name = "costguard.gateway.transport", havingValue = "kafka", matchIfMissing = true)
    static class KafkaConfig {

        @Bean
        SchemaRegistryClient schemaRegistryClient(
                @Value("${costguard.gateway.schema-registry-url:http://localhost:8085}") URI url) {
            return new SchemaRegistryClient(url);
        }

        /** Fails fast when Kafka is away, so the operation goes to the disk buffer instead of blocking the agent. */
        @Bean
        KafkaTemplate<String, byte[]> gatewayKafkaTemplate(KafkaProperties properties) {
            var config = properties.buildProducerProperties();
            config.put("key.serializer", StringSerializer.class);
            config.put("value.serializer", ByteArraySerializer.class);
            config.put("acks", "all");
            config.put("enable.idempotence", true);
            config.put("max.block.ms", 2000);
            config.put("delivery.timeout.ms", 15000);
            config.put("request.timeout.ms", 5000);
            var template = new KafkaTemplate<String, byte[]>(new DefaultKafkaProducerFactory<>(config));
            // a producer span and the W3C traceparent header: the Python services continue the trace (ADR 0050)
            template.setObservationEnabled(true);
            return template;
        }

        @Bean
        OperationLog operationLog(KafkaTemplate<String, byte[]> gatewayKafkaTemplate, SchemaRegistryClient registry,
                @Value(BUFFER) Path buffer) {
            return new OperationLog(gatewayKafkaTemplate, registry, buffer);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, byte[]> stateListenerFactory(KafkaProperties properties) {
            var config = properties.buildConsumerProperties();
            config.put("key.deserializer", StringDeserializer.class);
            config.put("value.deserializer", ByteArrayDeserializer.class);
            config.put("auto.offset.reset", "earliest");
            config.put("enable.auto.commit", false);
            var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
            return factory;
        }
    }
}
