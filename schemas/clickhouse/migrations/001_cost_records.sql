-- FOCUS cost records from every source: agent operations, invoice lines, payments, subscriptions.
-- A record keeps its id when its source changes it (a re-imported invoice line), so the newest version wins.
CREATE TABLE IF NOT EXISTS cost_records
(
    id                    UUID,
    tenant_id             UUID,
    source                LowCardinality(String),
    source_id             String,
    billed_cost           Decimal(18, 6),
    effective_cost        Decimal(18, 6),
    billing_currency      LowCardinality(String),
    charge_period_start   Date,
    charge_period_end     Date,
    service_provider_name String,
    department_id         Nullable(UUID),
    ingested_at           DateTime64(3) DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(charge_period_start)
ORDER BY (tenant_id, charge_period_start, id);

CREATE TABLE IF NOT EXISTS cost_records_queue
(
    id                    UUID,
    tenant_id             UUID,
    source                String,
    source_id             String,
    billed_cost           Decimal(18, 6),
    effective_cost        Decimal(18, 6),
    billing_currency      String,
    charge_period_start   Date,
    charge_period_end     Date,
    service_provider_name String,
    department_id         Nullable(UUID)
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '${KAFKA_BROKERS}',
         kafka_topic_list = 'cost.records.v1',
         kafka_group_name = 'clickhouse-cost-records',
         kafka_format = 'AvroConfluent',
         format_avro_schema_registry_url = '${SCHEMA_REGISTRY_URL}',
         kafka_handle_error_mode = 'stream';

CREATE MATERIALIZED VIEW IF NOT EXISTS cost_records_mv TO cost_records AS
SELECT id, tenant_id, source, source_id, billed_cost, effective_cost, billing_currency, charge_period_start,
       charge_period_end, service_provider_name, department_id, now64(3) AS ingested_at
FROM cost_records_queue
WHERE length(_error) = 0;

-- Messages the Avro decoder could not read, so they are seen instead of stopping the consumer.
CREATE TABLE IF NOT EXISTS kafka_errors
(
    topic   LowCardinality(String),
    offset  UInt64,
    raw     String,
    error   String,
    seen_at DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY (topic, seen_at)
TTL seen_at + INTERVAL 30 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS cost_records_errors_mv TO kafka_errors AS
SELECT _topic AS topic, _offset AS offset, _raw_message AS raw, _error AS error
FROM cost_records_queue
WHERE length(_error) > 0;
