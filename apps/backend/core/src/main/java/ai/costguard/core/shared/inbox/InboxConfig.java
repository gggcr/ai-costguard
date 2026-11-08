package ai.costguard.core.shared.inbox;

import ai.costguard.core.agents.MandateNotarizations;
import ai.costguard.core.approvals.ApprovalRequests;
import ai.costguard.core.approvals.OperationConfirmations;
import ai.costguard.core.connections.ConnectorRuns;
import ai.costguard.core.findings.FindingsIntake;
import ai.costguard.core.imports.ImportResults;
import ai.costguard.core.imports.NormalizedEntities;
import ai.costguard.core.journal.Receipts;
import ai.costguard.core.ledger.OperationPosting;
import ai.costguard.events.AgentOperation;
import ai.costguard.events.ConnectorRun;
import ai.costguard.events.Finding;
import ai.costguard.events.JournalAnchor;
import ai.costguard.events.NotaryConfirmation;
import ai.costguard.events.IngestNormalized;
import ai.costguard.events.IngestResult;
import ai.costguard.events.wire.AvroWire;
import ai.costguard.events.wire.SchemaRegistryClient;
import java.net.URI;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads {@code agent.operations.v1}, {@code ingest.results.v1}, {@code ingest.normalized.v1},
 * {@code findings.v1}, {@code journal.anchors.v1}, {@code notary.confirmations.v1} and {@code connectors.runs.v1}. The offset is committed only after the handler returns, so
 * a crash replays the message instead of losing it.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(name = "costguard.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
class InboxConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> inboxListenerFactory(KafkaProperties properties) {
        var config = properties.buildConsumerProperties();
        config.put("key.deserializer", StringDeserializer.class);
        config.put("value.deserializer", ByteArrayDeserializer.class);
        config.put("enable.auto.commit", false);
        config.put("auto.offset.reset", "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }

    @Bean
    OperationInbox operationInbox(OperationPosting ledger, Receipts receipts, ApprovalRequests approvals) {
        return new OperationInbox(ledger, receipts, approvals);
    }

    @Bean
    ImportResultInbox importResultInbox(ImportResults imports) {
        return new ImportResultInbox(imports);
    }

    @Bean
    NormalizedInbox normalizedInbox(NormalizedEntities entities) {
        return new NormalizedInbox(entities);
    }

    @Bean
    FindingInbox findingInbox(FindingsIntake intake) {
        return new FindingInbox(intake);
    }

    @Bean
    AnchorInbox anchorInbox(Receipts receipts) {
        return new AnchorInbox(receipts);
    }

    @Bean
    NotaryInbox notaryInbox(MandateNotarizations mandates, OperationConfirmations operations) {
        return new NotaryInbox(mandates, operations);
    }

    @Bean
    ConnectorRunInbox connectorRunInbox(ConnectorRuns runs) {
        return new ConnectorRunInbox(runs);
    }

    @Component
    @ConditionalOnProperty(name = "costguard.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
    static class Listener {

        private final OperationInbox inbox;
        private final ImportResultInbox imports;
        private final NormalizedInbox normalized;
        private final FindingInbox findings;
        private final AnchorInbox anchors;
        private final NotaryInbox notary;
        private final ConnectorRunInbox connectorRuns;
        private final SchemaRegistryClient registry;

        Listener(OperationInbox inbox, ImportResultInbox imports, NormalizedInbox normalized, FindingInbox findings,
                AnchorInbox anchors, NotaryInbox notary, ConnectorRunInbox connectorRuns,
                @Value("${costguard.events.schema-registry-url:http://localhost:8085}") URI registry) {
            this.inbox = inbox;
            this.imports = imports;
            this.normalized = normalized;
            this.findings = findings;
            this.anchors = anchors;
            this.notary = notary;
            this.connectorRuns = connectorRuns;
            this.registry = new SchemaRegistryClient(registry);
        }

        @KafkaListener(topics = "agent.operations.v1", groupId = "core-operations",
                containerFactory = "inboxListenerFactory")
        void on(ConsumerRecord<String, byte[]> record) {
            inbox.handle(AvroWire.decode(record.value(), registry, AgentOperation.class));
        }

        @KafkaListener(topics = "ingest.results.v1", groupId = "core-imports",
                containerFactory = "inboxListenerFactory")
        void onIngestResult(ConsumerRecord<String, byte[]> record) {
            imports.handle(AvroWire.decode(record.value(), registry, IngestResult.class));
        }

        @KafkaListener(topics = "ingest.normalized.v1", groupId = "core-entities",
                containerFactory = "inboxListenerFactory")
        void onNormalized(ConsumerRecord<String, byte[]> record) {
            normalized.handle(AvroWire.decode(record.value(), registry, IngestNormalized.class));
        }

        @KafkaListener(topics = "findings.v1", groupId = "core-findings", containerFactory = "inboxListenerFactory")
        void onFinding(ConsumerRecord<String, byte[]> record) {
            findings.handle(AvroWire.decode(record.value(), registry, Finding.class));
        }

        @KafkaListener(topics = "journal.anchors.v1", groupId = "core-journal", containerFactory = "inboxListenerFactory")
        void onAnchor(ConsumerRecord<String, byte[]> record) {
            anchors.handle(AvroWire.decode(record.value(), registry, JournalAnchor.class));
        }

        @KafkaListener(topics = "notary.confirmations.v1", groupId = "core-notary",
                containerFactory = "inboxListenerFactory")
        void onNotary(ConsumerRecord<String, byte[]> record) {
            notary.handle(AvroWire.decode(record.value(), registry, NotaryConfirmation.class));
        }

        @KafkaListener(topics = "connectors.runs.v1", groupId = "core-connections",
                containerFactory = "inboxListenerFactory")
        void onConnectorRun(ConsumerRecord<String, byte[]> record) {
            connectorRuns.handle(AvroWire.decode(record.value(), registry, ConnectorRun.class));
        }
    }
}
