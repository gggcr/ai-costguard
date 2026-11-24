package ai.costguard.core.journal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The operation journal of installs without ClickHouse (ADR 0080): the core keeps every decision of the gateway in
 * PostgreSQL, and the journal page reads it from there. With ClickHouse it reads agent_operations instead, which
 * ClickHouse fills from Kafka itself.
 */
public interface OperationJournal {

    /** One decision of the gateway; kind and decision are the names of the Avro enums (LLM_CALL, ALLOWED). */
    record Entry(UUID tenantId, UUID operationId, UUID agentId, Instant occurredAt, String kind, String provider,
            String model, String decision, String denialCode, BigDecimal estimatedCost, BigDecimal actualCost,
            String currency, Long inputTokens, Long outputTokens) {
    }

    /**
     * Records the decision. Safe to repeat; a later decision on the same operation (allowed after an approval)
     * replaces an earlier one, an earlier one arriving late does not.
     */
    void record(Entry entry);
}
