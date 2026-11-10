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
import ai.costguard.core.plans.TenantPlanChanged;
import ai.costguard.core.policies.PolicySnapshotPublished;
import ai.costguard.events.AgentState;
import ai.costguard.events.ApprovalDecision;
import ai.costguard.events.ApprovalOutcome;
import ai.costguard.events.BudgetLevel;
import ai.costguard.events.AssetAssignmentChanged;
import ai.costguard.events.AssetKind;
import ai.costguard.events.AssetState;
import ai.costguard.events.BudgetLimit;
import ai.costguard.events.CatalogPrice;
import ai.costguard.events.ConnectorConfig;
import ai.costguard.events.CostRecord;
import ai.costguard.events.CostSource;
import ai.costguard.events.IngestAccepted;
import ai.costguard.events.IngestFile;
import ai.costguard.events.JournalReceipt;
import ai.costguard.events.NotaryRequest;
import ai.costguard.events.NotaryRequestKind;
import ai.costguard.events.PolicyEffect;
import ai.costguard.events.PolicyRule;
import ai.costguard.events.PolicySnapshot;
import ai.costguard.events.PriceUnit;
import ai.costguard.events.TenantPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;

/** One place that turns module events into topic records; the topic and key follow schemas/avro/README.md. */
final class EventMapping {

    record Message(String topic, String key, SpecificRecord record) {
    }

    private EventMapping() {
    }

    /** The schemas use decimal(18, 6), and Avro refuses a value of another scale. */
    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_EVEN);
    }

    static Message of(AgentStateChanged e) {
        return new Message("agents.state.v1", e.agentId().toString(), AgentState.newBuilder()
                .setTenantId(e.tenantId()).setAgentId(e.agentId()).setVirtualKeyHashes(e.virtualKeyHashes())
                .setSuspended(e.suspended()).setMandateVersion(e.mandateVersion())
                .setMandateExpression(e.mandateExpression()).setApprovalThreshold(money(e.approvalThreshold()))
                .setUpdatedAt(e.updatedAt()).build());
    }

    static Message of(PolicySnapshotPublished e) {
        return new Message("policy.snapshots.v1", e.tenantId().toString(), PolicySnapshot.newBuilder()
                .setTenantId(e.tenantId()).setVersion(e.version())
                .setRules(e.rules().stream().map(r -> PolicyRule.newBuilder().setId(r.id()).setName(r.name())
                        .setExpression(r.expression()).setEffect(PolicyEffect.valueOf(r.effect())).build()).toList())
                .setPublishedAt(e.publishedAt()).build());
    }

    static Message of(CatalogPriceChanged e) {
        return new Message("catalog.prices.v1", e.tenantId() + ":" + e.skuId(), CatalogPrice.newBuilder()
                .setTenantId(e.tenantId()).setSkuId(e.skuId()).setProvider(e.vendor()).setModel(e.model())
                .setUnit(PriceUnit.valueOf(e.unit())).setUnitPrice(money(e.unitPrice())).setCurrency(e.currency())
                .setValidFrom(e.validFrom()).build());
    }

    static Message of(BudgetLimitChanged e) {
        return new Message("budgets.limits.v1", e.budgetId().toString(), BudgetLimit.newBuilder()
                .setTenantId(e.tenantId()).setBudgetId(e.budgetId()).setParentBudgetId(e.parentBudgetId())
                .setLevel(BudgetLevel.valueOf(e.level().toUpperCase(java.util.Locale.ROOT))).setRefId(e.refId())
                .setLimitAmount(money(e.limitAmount())).setCurrency(e.currency()).setCorrection(money(BigDecimal.ZERO))
                .setPeriodStart(java.time.LocalDate.of(1970, 1, 1)).setPeriodEnd(java.time.LocalDate.of(9999, 12, 31))
                .setVersion(e.version())
                .setLedgerSpent(e.ledgerSpent() == null ? null : money(e.ledgerSpent()))
                .setLedgerAsOf(e.ledgerAsOf()).build());
    }

    static Message of(TenantPlanChanged e) {
        return new Message("tenants.plans.v1", e.tenantId().toString(), TenantPlan.newBuilder()
                .setTenantId(e.tenantId()).setPlan(e.plan()).setReadOnly(e.readOnly())
                .setOperationsPerMonth(e.operationsPerMonth()).setAccessUntil(e.accessUntil())
                .setVersion(e.version()).setUpdatedAt(e.at()).build());
    }

    static Message of(ApprovalDecided e) {
        return new Message("approvals.decisions.v1", e.operationId().toString(), ApprovalDecision.newBuilder()
                .setTenantId(e.tenantId()).setApprovalId(e.approvalId()).setOperationId(e.operationId())
                .setAgentId(e.agentId()).setStatus(ApprovalOutcome.valueOf(e.status().toUpperCase(java.util.Locale.ROOT)))
                .setAmount(money(e.amount())).setDecidedAt(e.decidedAt()).build());
    }

    static Message of(ReceiptIssued e) {
        return new Message("journal.receipts.v1", e.receiptId().toString(), JournalReceipt.newBuilder()
                .setReceiptId(e.receiptId()).setTenantId(e.tenantId()).setOperationId(e.operationId())
                .setHash(e.hash()).setPreviousHash(e.previousHash()).setSignature(e.signature())
                .setSigningKeyId(e.signingKeyId()).setIssuedAt(e.issuedAt()).build());
    }

    /** The request id is derived from the version, so the outbox sending it again sends the same request. */
    static Message of(MandatePublished e) {
        UUID id = UUID.nameUUIDFromBytes(("notary:mandate:" + e.mandateId() + ":" + e.version()).getBytes());
        return new Message("notary.requests.v1", id.toString(), NotaryRequest.newBuilder()
                .setRequestId(id).setTenantId(e.tenantId()).setKind(NotaryRequestKind.MANDATE)
                .setSubjectId(e.mandateId()).setMandateVersion(e.version()).setSubjectHash(e.mandateHash())
                .setApproverHash(null).setRequestedAt(e.publishedAt()).build());
    }

    static Message of(LargeOperationApproved e) {
        UUID id = UUID.nameUUIDFromBytes(("notary:operation:" + e.operationId()).getBytes());
        return new Message("notary.requests.v1", id.toString(), NotaryRequest.newBuilder()
                .setRequestId(id).setTenantId(e.tenantId()).setKind(NotaryRequestKind.OPERATION)
                .setSubjectId(e.operationId()).setMandateVersion(null).setSubjectHash(e.operationHash())
                .setApproverHash(e.approverHash()).setRequestedAt(e.approvedAt()).build());
    }

    /** Keyed by tenant and type: one connection per type, and compaction keeps the latest state of each. */
    static Message of(ConnectionChanged e) {
        return new Message("connectors.config.v1", e.tenantId() + ":" + e.type(), ConnectorConfig.newBuilder()
                .setTenantId(e.tenantId()).setConnectionId(e.connectionId()).setType(e.type()).setName(e.name())
                .setEnabled(e.enabled()).setSettings(new java.util.HashMap<>(e.settings()))
                .setSyncRequestedAt(e.syncRequestedAt()).setUpdatedAt(e.updatedAt()).build());
    }

    static Message of(ImportFileUploaded e) {
        return new Message("ingest.files.v1", e.fileId().toString(), IngestFile.newBuilder()
                .setFileId(e.fileId()).setTenantId(e.tenantId()).setS3Key(e.storageKey()).setSha256(e.sha256())
                .setFileName(e.fileName()).setContentType(e.contentType() == null ? "application/octet-stream"
                        : e.contentType())
                .setSourceTemplateId(null).setUploadedAt(e.uploadedAt()).build());
    }

    static Message of(ImportAccepted e) {
        java.util.Map<String, java.util.Map<String, String>> corrections = new java.util.HashMap<>();
        e.corrections().forEach((row, fields) -> corrections.put(row.toString(), fields));
        return new Message("ingest.accepted.v1", e.fileId().toString(), IngestAccepted.newBuilder()
                .setFileId(e.fileId()).setTenantId(e.tenantId()).setS3Key(e.storageKey()).setFileName(e.fileName())
                .setVendorId(e.vendorId()).setMapping(e.mapping()).setCorrections(corrections)
                .setAcceptedAt(e.acceptedAt()).build());
    }

    static Message of(OperationCharged e) {
        java.time.LocalDate day = e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        return new Message("cost.records.v1", e.tenantId().toString(), CostRecord.newBuilder()
                .setId(UUID.nameUUIDFromBytes(("operation:" + e.operationId()).getBytes()))
                .setTenantId(e.tenantId()).setSource(CostSource.OPERATION).setSourceId(e.operationId().toString())
                .setBilledCost(money(e.amount())).setEffectiveCost(money(e.amount())).setBillingCurrency(e.currency())
                .setChargePeriodStart(day).setChargePeriodEnd(day).setServiceProviderName("agent " + e.agentId())
                .setDepartmentId(null).build());
    }

    static Message of(AssetChanged e) {
        return new Message("assets.assignments.v1", e.assetId().toString(), AssetAssignmentChanged.newBuilder()
                .setAssetId(e.assetId()).setTenantId(e.tenantId()).setInventoryNumber(e.inventoryNumber())
                .setModel(e.model()).setType(AssetKind.valueOf(e.type())).setCost(money(e.cost()))
                .setStatus(AssetState.valueOf(e.status())).setEmployeeId(e.employeeId()).setIssuedAt(e.issuedAt())
                .setReturnDue(e.returnDue()).setChangedAt(e.changedAt()).build());
    }
}
