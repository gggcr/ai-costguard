# Kafka event schemas

The schema comes first: add `.avsc` files here, and `:events` generates the Java classes from them.

| Topic | Producer | Consumers |
|---|---|---|
| `agent.operations.v1` | Agent gateway | Core ledger and journal, anomaly detector, ClickHouse |
| `agent.risk-signals.v1` (compacted) | Anomaly detector | Agent gateway |
| `policy.snapshots.v1` | Core policies | Agent gateway |
| `journal.receipts.v1` | Core ledger and journal | NEAR notary |
| `journal.anchors.v1` | NEAR notary | Core ledger and journal |
| `ingest.files.v1` | Core imports | Ingest pipeline |
| `ingest.normalized.v1` | Ingest pipeline | Core master data |
| `cost.records.v1` | Ingest pipeline, core ledger | ClickHouse, forecast service |
| `findings.v1` | Audit engine | Core findings and notifications |
