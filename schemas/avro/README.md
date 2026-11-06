# Kafka event schemas

The schema comes first: add `.avsc` files here, and `:events` generates the Java classes from them.

| Topic | Producer | Consumers | Key |
|---|---|---|---|
| `agent.operations.v1` | Agent gateway | Core ledger and journal, anomaly detector, ClickHouse | agent_id |
| `agent.risk-signals.v1` (compacted) | Anomaly detector | Agent gateway, core agents and notifications | agent_id or task_id |
| `policy.snapshots.v1` (compacted) | Core policies | Agent gateway | tenant_id |
| `agents.state.v1` (compacted) | Core agents | Agent gateway | agent_id |
| `catalog.prices.v1` (compacted) | Core catalog | Agent gateway | sku_id |
| `budgets.limits.v1` (compacted) | Core ledger | Agent gateway | budget_id |
| `journal.receipts.v1` | Core journal | NEAR notary | receipt_id |
| `journal.anchors.v1` | NEAR notary | Core journal | batch_id |
| `ingest.files.v1` | Core imports | Ingest pipeline | file_id |
| `ingest.normalized.v1` | Ingest pipeline | Core software and org | entity key |
| `cost.records.v1` | Ingest pipeline, core ledger | ClickHouse, forecast service | tenant_id |
| `findings.v1` | Audit engine | Core findings and notifications | finding fingerprint |

`agents.state.v1` carries the virtual key hash, agent status (suspended or not) and the active mandate version.
Budget counters in Redis are written only by the gateway. The core ledger reconciles them against PostgreSQL and publishes corrections in `budgets.limits.v1`; the gateway applies them.

The `assets` module publishes no events yet, and the board has no topic for it. See
[the comparison](../../docs/architecture/assets-vs-board.md) for what the audit engine needs.
