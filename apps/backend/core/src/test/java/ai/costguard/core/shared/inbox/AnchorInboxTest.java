package ai.costguard.core.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.costguard.core.journal.Receipts;
import ai.costguard.events.JournalAnchor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnchorInboxTest {

    final List<UUID> anchored = new ArrayList<>();

    final Receipts receipts = new Receipts() {
        @Override
        public UUID issue(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, String currency,
                Instant occurredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void anchored(UUID tenantId, UUID batchId, String merkleRoot, List<UUID> receiptIds,
                String nearTxHash, Instant anchoredAt) {
            anchored.add(tenantId);
        }
    };

    JournalAnchor anchor(UUID tenant) {
        Instant now = Instant.now();
        return JournalAnchor.newBuilder().setBatchId(UUID.randomUUID()).setTenantId(tenant).setMerkleRoot("ab")
                .setReceiptCount(1).setReceiptIds(List.of(UUID.randomUUID())).setPeriodStart(now).setPeriodEnd(now)
                .setNearTxHash("TX").setAnchoredAt(now).build();
    }

    @Test
    void anchorsGoToTheJournal() {
        UUID tenant = UUID.randomUUID();
        new AnchorInbox(receipts).handle(anchor(tenant));
        assertThat(anchored).containsExactly(tenant);
    }

    @Test
    void anOldAnchorWithoutATenantIsSkipped() {
        new AnchorInbox(receipts).handle(anchor(null));
        assertThat(anchored).isEmpty();
    }
}
