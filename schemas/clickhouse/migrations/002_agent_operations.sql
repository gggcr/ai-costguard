-- Every decision of the gateway. A held operation is reported again when it is decided, with the same
-- operation id and a later time, so the latest report wins.
CREATE TABLE IF NOT EXISTS agent_operations
(
    operation_id    UUID,
    tenant_id       UUID,
    agent_id        UUID,
    task_id         Nullable(String),
    kind            LowCardinality(String),
    provider        LowCardinality(String),
    model           Nullable(String),
    decision        LowCardinality(String),
    denial_code     Nullable(String),
    estimated_cost  Decimal(18, 6),
    actual_cost     Nullable(Decimal(18, 6)),
    currency        LowCardinality(String),
    input_tokens    Nullable(Int64),
    output_tokens   Nullable(Int64),
    mandate_version Nullable(Int32),
    prompt_simhash  Nullable(Int64),
    occurred_at     DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree(occurred_at)
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (tenant_id, agent_id, operation_id);

CREATE TABLE IF NOT EXISTS agent_operations_queue
(
    operation_id    UUID,
    tenant_id       UUID,
    agent_id        UUID,
    task_id         Nullable(String),
    kind            String,
    provider        String,
    model           Nullable(String),
    decision        String,
    denial_code     Nullable(String),
    estimated_cost  Decimal(18, 6),
    actual_cost     Nullable(Decimal(18, 6)),
    currency        String,
    input_tokens    Nullable(Int64),
    output_tokens   Nullable(Int64),
    mandate_version Nullable(Int32),
    prompt_simhash  Nullable(Int64),
    occurred_at     DateTime64(3, 'UTC')
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '${KAFKA_BROKERS}',
         kafka_topic_list = 'agent.operations.v1',
         kafka_group_name = 'clickhouse-agent-operations',
         kafka_format = 'AvroConfluent',
         format_avro_schema_registry_url = '${SCHEMA_REGISTRY_URL}',
         kafka_handle_error_mode = 'stream';

CREATE MATERIALIZED VIEW IF NOT EXISTS agent_operations_mv TO agent_operations AS
SELECT *
FROM agent_operations_queue
WHERE length(_error) = 0;

CREATE MATERIALIZED VIEW IF NOT EXISTS agent_operations_errors_mv TO kafka_errors AS
SELECT _topic AS topic, _offset AS offset, _raw_message AS raw, _error AS error
FROM agent_operations_queue
WHERE length(_error) > 0;
