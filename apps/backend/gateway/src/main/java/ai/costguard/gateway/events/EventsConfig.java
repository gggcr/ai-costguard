package ai.costguard.gateway.events;

import ai.costguard.events.wire.SchemaRegistryClient;
import java.net.URI;
import java.time.Clock;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
class EventsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

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
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
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
