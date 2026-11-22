# Kafka event schemas

The schema comes first: add `.avsc` files here, and `:events` generates the Java classes from them.

| Topic | Producer | Consumers | Key |
|---|---|---|---|
| `agent.operations.v1` | Agent gateway | Core ledger and journal, anomaly detector, ClickHouse | agent_id |
| `agent.risk-signals.v1` (compacted) | Anomaly detector | Agent gateway, core agents and notifications | agent_id, or agent_id:task_id for a task |
| `policy.snapshots.v1` (compacted) | Core policies | Agent gateway | tenant_id |
| `agents.state.v1` (compacted) | Core agents | Agent gateway | agent_id |
| `catalog.prices.v1` (compacted) | Core catalog | Agent gateway | tenant_id:sku_id |
| `catalog.rates.v1` (compacted) | Core catalog | Agent gateway | currency |
| `budgets.limits.v1` (compacted) | Core ledger | Agent gateway | budget_id |
| `journal.receipts.v1` | Core journal | NEAR notary | receipt_id |
| `journal.anchors.v1` | NEAR notary | Core journal | batch_id |
| `ingest.files.v1` | Core imports | Ingest pipeline | file_id |
| `ingest.results.v1` | Ingest pipeline | Core imports | file_id |
| `ingest.accepted.v1` | Core imports | Ingest pipeline | file_id |
| `ingest.normalized.v1` | Ingest pipeline | Core software and org | entity key |
| `cost.records.v1` | Ingest pipeline, core ledger | ClickHouse, forecast service | tenant_id |
| `findings.v1` | Audit engine | Core findings and notifications | finding fingerprint |
| `assets.assignments.v1` | Core assets | Audit engine | asset_id |
| `approvals.decisions.v1` (compacted) | Core approvals | Agent gateway | operation_id |
| `notary.requests.v1` | Core agents and approvals | NEAR notary | request_id |
| `notary.confirmations.v1` | NEAR notary | Core agents and approvals | request_id |
| `connectors.config.v1` (compacted) | Core connections | Connector agent | tenant_id:type |
| `connectors.runs.v1` | Connector agent | Core connections | tenant_id:type |
| `tenants.plans.v1` (compacted) | Core plans | Agent gateway | tenant_id |

`agents.state.v1` carries the virtual key hash, agent status (suspended or not) and the active mandate version.
Budget counters in Redis are written only by the gateway. The core ledger reconciles them against PostgreSQL and publishes corrections in `budgets.limits.v1`; the gateway applies them.

`assets.assignments.v1` is not on the board yet: it closes the gap described in
[the comparison](../../docs/architecture/assets-vs-board.md).

`approvals.decisions.v1` is not on the board either: frame 3 retries an approved operation through the gateway,
and nothing else tells the gateway what was decided. `budgets.limits.v1` carries the level and `ref_id` of a
budget, so the gateway can find the budget of an agent and walk up to the company.

Prices are per tenant, so `catalog.prices.v1` is keyed by tenant and SKU: keyed by SKU alone, compaction
would keep one tenant's price and drop the others.

`catalog.rates.v1` is not on the board: the gateway charges a call in the currency of the company's budgets, and a
catalog price in another currency needs the Bank of Russia rate to get there
([ADR 0072](../../docs/adr/0072-company-currency-and-country.md)). The rates are the same for every company, so the
topic is keyed by currency and erasing a company sends it no tombstone.

The core publishes its topics from the Spring Modulith outbox (`ai.costguard.core.shared.outbox`): an event
is stored in the same transaction as the change and sent after commit, again until Kafka takes it. Messages
use the Schema Registry wire format: byte 0, a 4-byte schema id, then Avro binary.

`notary.requests.v1` and `notary.confirmations.v1` are not on the board either. The core asks the notary to write
the hash of every published mandate version and of every approved operation of at least
`COSTGUARD_NOTARY_CONFIRM_ABOVE`; the notary answers with the NEAR transaction. Only hashes reach the contract, see
[ADR 0009](../../docs/adr/0009-notary-mandates-confirmations.md).

`ingest.results.v1` and `ingest.accepted.v1` are not on the board: the board draws the pipeline reporting columns,
errors and unsure values back to people, and people releasing the rows, but no topic carries either step.

`connectors.config.v1` and `connectors.runs.v1` are not on the board: the page of connected systems needs
the core to tell the agent what to pull and the agent to tell the core how it went (ADR 0019).

Erasing a company (ADR 0021) sends a tombstone (null value) for each of its keys on the compacted topics the core
produces, so compaction drops them. The gateway skips tombstones on its state topics, except on `agents.state.v1`,
where it drops the agent and its keys at once.

Every producer and consumer is checked against these files (ADR 0018): `TopicContractTest` of the core and the
gateway, `EventMappingTest` for what the core publishes, and `tests/contracts` for the Python services, which also
feed every consumer random messages the schema allows. A listener added without its contract fails those tests.

`tenants.plans.v1` is not on the board: self-registration and pricing plans came after it
([ADR 0037](../../docs/adr/0037-pricing-plans-and-limits.md)). The gateway refuses operations of a read-only
company and counts the operations of the month against the plan.
