package ai.costguard.core.shared.outbox;

import ai.costguard.core.agents.AgentStateChanged;
import ai.costguard.core.agents.MandatePublished;
import ai.costguard.core.approvals.ApprovalDecided;
import ai.costguard.core.approvals.LargeOperationApproved;
import ai.costguard.core.assets.AssetChanged;
import ai.costguard.core.catalog.CatalogPriceChanged;
import ai.costguard.core.connections.ConnectionChanged;
import ai.costguard.core.imports.ImportAccepted;
import ai.costguard.core.imports.ImportFileUploaded;
import ai.costguard.core.journal.ReceiptIssued;
import ai.costguard.core.ledger.BudgetLimitChanged;
import ai.costguard.core.ledger.OperationCharged;
import ai.costguard.core.policies.PolicySnapshotPublished;
import ai.costguard.core.privacy.CompactedRecordsErased;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Each listener is an outbox entry: Spring Modulith stores the event with the change and calls the listener after
 * commit. A listener that throws, for example because Kafka is down, stays incomplete and is retried every minute
 * by {@link OutboxRetry} and on restart ({@code spring.modulith.events.republish-outstanding-events-on-restart}). Delivery is at least once, so
 * consumers dedupe by key.
 */
class KafkaOutbox {

    private final AvroPublisher publisher;

    KafkaOutbox(AvroPublisher publisher) {
        this.publisher = publisher;
    }

    private void send(EventMapping.Message m) {
        publisher.publish(m.topic(), m.key(), m.record());
    }

    @ApplicationModuleListener
    void on(AgentStateChanged e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(PolicySnapshotPublished e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(CatalogPriceChanged e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(BudgetLimitChanged e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(MandatePublished e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(LargeOperationApproved e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(ConnectionChanged e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(ReceiptIssued e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(ImportFileUploaded e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(ImportAccepted e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(OperationCharged e) {
        send(EventMapping.of(e));
    }

    @ApplicationModuleListener
    void on(AssetChanged e) {
        send(EventMapping.of(e));
    }

    /** After a company is erased. Sending a tombstone twice is harmless: the key is gone either way. */
    @ApplicationModuleListener
    void on(CompactedRecordsErased e) {
        e.keys().forEach(k -> publisher.tombstone(k.topic(), k.key()));
    }
}
