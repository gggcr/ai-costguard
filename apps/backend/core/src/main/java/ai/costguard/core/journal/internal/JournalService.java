package ai.costguard.core.journal.internal;

import ai.costguard.core.journal.ReceiptIssued;
import ai.costguard.core.journal.Receipts;
import ai.costguard.platform.tenant.TenantSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JournalService implements Receipts {

    private final JournalRepository journal;
    private final ReceiptSigner signer;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** Receipts issued and the last anchor from the notary, for the alert on anchoring (ADR 0026). */
    private final Counter issued;
    private final AtomicLong lastAnchor = new AtomicLong();

    JournalService(JournalRepository journal, ReceiptSigner signer, TenantSession session, Clock clock,
            ApplicationEventPublisher events, MeterRegistry registry) {
        this.journal = journal;
        this.signer = signer;
        this.session = session;
        this.clock = clock;
        this.events = events;
        this.issued = Counter.builder("costguard.journal.receipts.issued")
                .description("Receipts issued by this instance").register(registry);
        Gauge.builder("costguard.journal.last.anchor.timestamp.seconds", lastAnchor, AtomicLong::get)
                .description("When this instance last recorded an anchor from the NEAR notary; 0 since start")
                .baseUnit("seconds").register(registry);
    }

    /** Fixed field order, one per line, so anyone can rebuild the payload and its hash. */
    static String payload(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, String currency,
            Instant occurredAt) {
        return "tenant_id=" + tenantId + "\noperation_id=" + operationId + "\nagent_id=" + agentId
                + "\namount=" + amount.stripTrailingZeros().toPlainString() + "\ncurrency=" + currency
                + "\noccurred_at=" + occurredAt;
    }

    @Override
    @Transactional
    public UUID issue(UUID tenantId, UUID operationId, UUID agentId, BigDecimal amount, String currency,
            Instant occurredAt) {
        session.bind(tenantId);
        JournalRepository.ChainHead head = journal.lockHead(tenantId);
        Optional<StoredReceipt> existing = journal.byOperation(operationId);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        String payload = payload(tenantId, operationId, agentId, amount, currency, occurredAt);
        String hash = Hashes.link(head.hash(), payload);
        StoredReceipt receipt = new StoredReceipt(UUID.randomUUID(), operationId, head.seq() + 1, payload, hash,
                head.hash(), signer.sign(hash), signer.keyId(), clock.instant(), null);
        journal.insert(tenantId, receipt);
        journal.moveHead(tenantId, receipt.seq(), hash);
        events.publishEvent(new ReceiptIssued(receipt.id(), tenantId, operationId, hash, head.hash(),
                receipt.signature(), receipt.keyId(), receipt.issuedAt()));
        issued.increment();
        return receipt.id();
    }

    /** Refuses a root that does not match the receipts, so a wrong batch never marks receipts as anchored. */
    @Override
    @Transactional
    public void anchored(UUID tenantId, UUID batchId, String merkleRoot, List<UUID> receiptIds, String nearTxHash,
            Instant anchoredAt) {
        session.bind(tenantId);
        if (journal.batchExists(batchId)) {
            return;
        }
        List<String> leaves = journal.byIds(receiptIds).stream().sorted(Comparator.comparingLong(StoredReceipt::seq))
                .map(StoredReceipt::hash).toList();
        if (leaves.size() != receiptIds.size() || !Merkle.root(leaves).equals(merkleRoot)) {
            throw JournalProblem.ROOT_MISMATCH.failure();
        }
        journal.insertBatch(tenantId, batchId, merkleRoot, nearTxHash, anchoredAt);
        journal.assignBatch(receiptIds, batchId);
        lastAnchor.set(clock.instant().getEpochSecond());
    }

    @Transactional(readOnly = true)
    ReceiptView receipt(UUID tenantId, UUID operationId) {
        session.bind(tenantId);
        StoredReceipt r = journal.byOperation(operationId).orElseThrow(JournalProblem.RECEIPT_NOT_FOUND::failure);
        if (r.batchId() == null) {
            return new ReceiptView(r.id(), r.operationId(), r.payload(), r.hash(), r.previousHash(), r.signature(),
                    r.keyId(), ReceiptStatus.pending, null, null, null, List.of());
        }
        Batch batch = journal.batch(r.batchId()).orElseThrow();
        List<String> leaves = journal.batchHashes(r.batchId());
        return new ReceiptView(r.id(), r.operationId(), r.payload(), r.hash(), r.previousHash(), r.signature(),
                r.keyId(), ReceiptStatus.anchored, batch.id(), batch.merkleRoot(), batch.txHash(),
                Merkle.path(leaves, leaves.indexOf(r.hash())));
    }

    /** Walks the whole chain: every link, every hash and every signature by the current key. */
    @Transactional(readOnly = true)
    ChainVerification verify(UUID tenantId) {
        session.bind(tenantId);
        String previous = Hashes.GENESIS;
        long seq = 0;
        long count = 0;
        long unverified = 0;
        List<StoredReceipt> page;
        do {
            page = journal.inOrder(seq, 1000);
            for (StoredReceipt r : page) {
                if (r.seq() != seq + 1 || !r.previousHash().equals(previous)) {
                    return new ChainVerification(false, count, r.seq(), "link", unverified);
                }
                if (!Hashes.link(previous, r.payload()).equals(r.hash())) {
                    return new ChainVerification(false, count, r.seq(), "hash", unverified);
                }
                if (!signer.knows(r.keyId())) {
                    unverified++;
                } else if (!signer.verify(r.keyId(), r.hash(), r.signature())) {
                    return new ChainVerification(false, count, r.seq(), "signature", unverified);
                }
                previous = r.hash();
                seq = r.seq();
                count++;
            }
        } while (!page.isEmpty());
        return new ChainVerification(true, count, null, null, unverified);
    }

    SigningKey key() {
        return new SigningKey(signer.keyId(), "Ed25519", signer.publicKeyBase64());
    }

    java.util.List<SigningKey> keys() {
        return signer.keys();
    }
}
