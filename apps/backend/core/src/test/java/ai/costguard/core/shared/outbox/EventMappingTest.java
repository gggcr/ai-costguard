package ai.costguard.core.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.assets.AssetChanged;
import ai.costguard.core.catalog.CatalogPriceChanged;
import ai.costguard.core.imports.ImportAccepted;
import ai.costguard.core.imports.ImportFileUploaded;
import ai.costguard.core.journal.ReceiptIssued;
import ai.costguard.core.ledger.BudgetLimitChanged;
import ai.costguard.core.ledger.OperationCharged;
import ai.costguard.core.policies.PolicySnapshotPublished;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every mapping must build a record its schema accepts; encoding validates it field by field. It is also the
 * producer contract of the core: the record goes to a topic whose schema in schemas/avro is the record's own.
 */
class EventMappingTest {

    private final UUID tenant = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");

    private static void encodes(EventMapping.Message m) {
        assertThat(AvroPublisher.encode(1, m.record())).hasSizeGreaterThan(5);
        assertThat(ai.costguard.core.shared.TopicSchemas.contract(m.record().getSchema()))
                .as("schema of the record sent to %s", m.topic())
                .isEqualTo(ai.costguard.core.shared.TopicSchemas.of(m.topic()));
    }

    @Test
    void everyEventFitsItsSchema() {
        UUID id = UUID.randomUUID();
        encodes(EventMapping.of(new PolicySnapshotPublished(tenant, 2,
                List.of(new PolicySnapshotPublished.Rule(id, "n", "cost > 1.0", "DENY")), now)));
        encodes(EventMapping.of(new BudgetLimitChanged(tenant, id, null, "company", null, new BigDecimal("1000.5"), "RUB", 3)));
        encodes(EventMapping.of(new ai.costguard.core.plans.TenantPlanChanged(tenant, "trial", false, 10000L,
                now, 1, now)));
        encodes(EventMapping.of(new ai.costguard.core.plans.TenantPlanChanged(tenant, "business", true, null,
                null, 7, now)));
        encodes(EventMapping.of(new ai.costguard.core.approvals.ApprovalDecided(tenant, id, id, id, "approved",
                new BigDecimal("1500"), now)));
        encodes(EventMapping.of(new ReceiptIssued(id, tenant, id, "a".repeat(64), "0".repeat(64), "sig", "k", now)));
        encodes(EventMapping.of(new ImportFileUploaded(id, tenant, "t/sha", "sha", "a.csv", null, null, now)));
        encodes(EventMapping.of(new OperationCharged(tenant, id, id, new BigDecimal("12.345678"), "RUB", now)));
        encodes(EventMapping.of(new ImportAccepted(tenant, id, tenant + "/sha", "a.csv", null,
                java.util.Map.of("Сумма", "amount"), java.util.Map.of(2, java.util.Map.of("amount", "1500")), now)));
        encodes(EventMapping.of(new AssetChanged(tenant, id, "INV-1", "ThinkPad", "LAPTOP", new BigDecimal("80000"),
                "ASSIGNED", id, LocalDate.of(2026, 11, 1), null, now)));
        encodes(EventMapping.of(new ai.costguard.core.agents.MandatePublished(tenant, id, 2, "a".repeat(64), now)));
        encodes(EventMapping.of(new ai.costguard.core.approvals.LargeOperationApproved(tenant, id, id,
                "b".repeat(64), "c".repeat(64), now)));
        encodes(EventMapping.of(new ai.costguard.core.connections.ConnectionChanged(tenant, id, "okta", "Okta", true,
                java.util.Map.of("url", "https://romashka.okta.example"), now, now)));
        encodes(EventMapping.of(new ai.costguard.core.connections.ConnectionChanged(tenant, id, "onec", "1С", false,
                java.util.Map.of(), null, now)));
    }

    /** The outbox may send an event twice; the notary must see the same request id both times. */
    @Test
    void notaryRequestsAreKeyedByTheirSubject() {
        UUID mandate = UUID.randomUUID();
        var v2 = EventMapping.of(new ai.costguard.core.agents.MandatePublished(tenant, mandate, 2, "a".repeat(64), now));
        var again = EventMapping.of(new ai.costguard.core.agents.MandatePublished(tenant, mandate, 2, "a".repeat(64),
                now.plusSeconds(5)));
        var v3 = EventMapping.of(new ai.costguard.core.agents.MandatePublished(tenant, mandate, 3, "a".repeat(64), now));
        assertThat(v2.topic()).isEqualTo("notary.requests.v1");
        assertThat(again.key()).isEqualTo(v2.key());
        assertThat(v3.key()).isNotEqualTo(v2.key());

        UUID operation = UUID.randomUUID();
        var op = EventMapping.of(new ai.costguard.core.approvals.LargeOperationApproved(tenant, UUID.randomUUID(),
                operation, "b".repeat(64), "c".repeat(64), now));
        var request = (ai.costguard.events.NotaryRequest) op.record();
        assertThat(request.getKind()).isEqualTo(ai.costguard.events.NotaryRequestKind.OPERATION);
        assertThat(request.getSubjectId()).isEqualTo(operation);
        assertThat(op.key()).isEqualTo(request.getRequestId().toString()).isNotEqualTo(v2.key());
    }

    @Test
    void pricesAreKeyedByTenantAndSku() {
        EventMapping.Message m = EventMapping.of(new CatalogPriceChanged(tenant, "gpt-5:in", "OpenAI", "gpt-5",
                "INPUT_TOKEN", new BigDecimal("0.000002"), "USD", LocalDate.of(2026, 1, 1)));

        assertThat(m.key()).isEqualTo(tenant + ":gpt-5:in");
        encodes(m);
    }

    @Test
    void aRedeliveredChargeKeepsItsCostRecordId() {
        UUID operation = UUID.randomUUID();
        OperationCharged e = new OperationCharged(tenant, operation, UUID.randomUUID(), BigDecimal.ONE, "RUB", now);

        assertThat(((ai.costguard.events.CostRecord) EventMapping.of(e).record()).getId())
                .isEqualTo(((ai.costguard.events.CostRecord) EventMapping.of(e).record()).getId());
    }
}
