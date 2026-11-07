# CostGuard core

Modular monolith on Spring Modulith: one deployment and one transaction for a small team and SMB clients.
Board frames: 9 (data model), 10 (Kafka topics), 13 (inside the services).

## Module rules

A module does not read another module's tables or call its internal classes. Modules talk through events.
`ModuleBoundariesTest` enforces this, so breaking a boundary fails the build.

Each module is a direct subpackage of `ai.costguard.core`. Put its public API in the module's root package
and everything else in subpackages. Flyway migrations live in `src/main/resources/db/migration`.

## assets

Company equipment, who holds it and when it is due back. Comparison with the board:
[docs/architecture/assets-vs-board.md](../../../docs/architecture/assets-vs-board.md).

- **Asset**: one item of equipment. **Assignment**: one period an employee holds an asset; an open
  assignment has no `returnedAt`. **Retired**: no longer in use, kept for history.
- `cost` comes from outside. The core does not depreciate it: accounting stays in the accounting system.
- The tenant comes from the `tenant_id` token claim. An administrator sets it as a Keycloak user attribute;
  users cannot change it, and a user without it gets 403 `tenant_missing`. After a realm change, recreate
  the container: `--import-realm` skips a realm that already exists.
- `owner`, `finance` and `it_admin` can change equipment; see `ManagesAssets`. Anyone in the tenant can read it.
- `TenantSession` binds the tenant to the transaction, and RLS
  alone isolates the rows, so queries have no tenant filter.

## org

Company, departments, employees and CostGuard users. `owner` and `it_admin` can change them.

- Dismissal sets `dismissedAt` and publishes `EmployeeDismissed` once; repeating the call changes nothing.
- `GET /api/v1/employees/{id}/dismissal-checklist` is assembled from `DismissalChecklistSection` beans, so a
  module adds its part without `org` depending on it: `assets` adds equipment, `software` adds licenses.
- `user_account` is not synced with Keycloak yet: roles in tokens still come from the realm.

## agents

Agents, virtual keys and mandates. Every change publishes `AgentStateChanged`, the full state for the gateway.

- A key secret is returned once. Only its SHA-256 is stored and sent to the gateway.
- `owner` and `it_admin` change any agent. `agent_owner` changes only agents whose `ownerId` is the employee
  linked to their login by the `email` token claim, and cannot hand an agent over.
- An edited mandate is a draft until published, and agents keep the last published terms meanwhile. A mandate
  never published has no terms: the event has no expression and the gateway must deny the agent.
  `MandateExpression` compiles a mandate to CEL.
- `departmentId` and `ownerId` are not checked against `org`: the tables belong to that module.

## ledger

Budgets of three levels (company, department, agent) and the journal of entries.

- `OperationPosting.charge` posts an operation to the agent's budget and every budget above it. It is safe to
  repeat, because Kafka delivers at least once. A database trigger keeps the journal append-only: a mistake is
  corrected with a reversal.
- `spent` is summed from the journal. The amount reserved right now lives only in the gateway's Redis.
- Reconciliation against Redis is not here yet: nothing reads the gateway's counters.

## approvals

Operations the gateway held back for a human. `ApprovalRequests.open` is safe to repeat for one operation.

- `owner` and `finance` decide on any agent, `agent_owner` only on their own. A closed or overdue request
  answers 409.
- `ApprovalTimer` publishes `ApprovalEscalated` at half of `costguard.approvals.ttl` (4 hours by default), then
  closes the request as `expired`.
- The timer serves all tenants while RLS shows it none, so `approval_timer` has no RLS. It holds only ids and
  times; the work itself runs in each tenant's session. Do not add business data to it.
- Nothing calls `open` yet, and the gateway does not learn the outcome yet: that comes with the gateway work.

## policies

Company rules on top of agent mandates. A rule is a CEL condition over the operation and an effect, `DENY` or
`REQUIRE_APPROVAL`; when several match, `DENY` wins.

- The variables and the evaluator live in `platform-starter` (`OperationRules`), so the gateway evaluates exactly
  what the core checked. An expression that does not compile is refused on save. An error while evaluating counts
  as a match, so a broken rule denies instead of letting money through.
- The gateway sees rules only after `POST /api/v1/policies/publication`, which sends every enabled rule.
- Simulation runs on operations given in the request. Running it on the last 14 days needs operation history from
  ClickHouse, which is not there yet.

## catalog

Prices of models and paid APIs, per tenant, and Bank of Russia rates for everyone.

- A price change is a new row from a date: past operations keep the price they were estimated with.
- `CbrRatesLoader` reads `XML_daily.asp` on start and every 6 hours; a failure keeps earlier rates.
  `CBR_RATES_ENABLED=false` turns it off. `fx_rate` has no tenant and no RLS: the rates are public.

## journal

Receipts of allowed operations. `Receipts.issue` is safe to repeat for one operation.

- Each tenant has a hash chain: `hash = sha256(previous_hash + "\n" + payload)`, from 64 zeros. The payload
  is `key=value` lines in a fixed order. The hash is signed with Ed25519; `GET /api/v1/journal/signing-key` gives
  the public key.
- A trigger makes receipts immutable, except for recording the batch that anchored them.
- `Receipts.anchored` checks the Merkle root against the receipts before marking them. The tree is described in
  `Merkle`; the NEAR notary must build the same one.
- Without `JOURNAL_PRIVATE_KEY` and `JOURNAL_PUBLIC_KEY` the core signs with a key for one run and warns. Such
  receipts cannot be verified after a restart.

## software

Vendors, contracts, subscriptions and licenses. `owner`, `finance` and `it_admin` change them.

- A license cannot be given beyond the seats of its subscription, and seats cannot drop below the licenses given.
  A subscription can only point at a contract of its own vendor.
- `lastActive` only moves forward, so a late connector report never hides a newer login. The audit engine needs
  it for unused licenses.

## findings

Findings of the audit engine, frame 8 of the board. `FindingsIntake.detected` creates a finding by its
fingerprint, refreshes it, or reopens it if it had closed by itself; `resolved` closes an open one.

- Actions follow the same table as `FINDING_TRANSITIONS` in the frontend. Two invoices in a row without the
  saving send it back to review. A postponed finding becomes new on its date; `finding_wakeup` has no RLS for
  the same reason as `approval_timer`.
- Savings of equipment findings do not show in invoices, so such findings still cannot be confirmed: board gap 3.

## notifications

One feed per tenant. Events of other modules become notifications after their transaction commits, and the outbox
retries a failed listener. `GET /api/v1/notifications/stream` pushes new ones as Server-Sent Events; with several
instances a client gets the ones its instance stored, and a reconnect reads the rest from the feed.
Telegram and e-mail delivery are not there yet.

## imports

Uploads and their way to the ingest pipeline. A file is stored under `tenant/sha256`, so uploading it again
returns the first import. The pipeline reports through `ImportResults`; people confirm the column mapping and
resolve uncertain values, and then `ImportAccepted` tells the pipeline to write the rows.

- Files go to local disk under `costguard.imports.storage-dir`. The board keeps them in S3 (SeaweedFS locally):
  `FileStore` is the only class to replace.
- `NormalizedEntities` takes `ingest.normalized.v1` from the pipeline and the connectors and keeps every entity in
  `ingest_entity` under its key. Vendors are merged by INN, then by the cleaned name (`shared/names/Names`, the
  same rules as `costguard_common.normalized`), and created when new. Employees are matched by e-mail, then by a
  unique full name, and never created: an employee needs a department. A license is tied when vendor,
  subscription and person are known and the subscription has a free seat; its activity only moves forward.
  Unmatched entities (`linked_id is null`) wait for people; there is no screen for them yet.

## analytics

`GET /api/v1/analytics/summary` gives agent spend per month from the ledger and findings totals. Software costs
from invoices, forecasts and XLSX/PDF reports need ClickHouse and the forecast service, which are not there yet.

## Events to Kafka

`shared/outbox` publishes module events to the topics of `schemas/avro`: agent state, policy snapshots, prices,
budget limits, receipts, uploaded files, charged operations as cost records, and asset changes. Each listener is an
outbox entry stored with the change and retried until Kafka takes the message; `KafkaOutbox` documents this.
`CORE_EVENTS_KAFKA_ENABLED=false` keeps events inside the core.

Not published yet: approval decisions (the board has no topic the gateway reads them from) and accepted imports
(none for the pipeline either). The core does not consume any topic yet.

## Events from Kafka

`shared/inbox` reads `agent.operations.v1`: an allowed operation is charged to the ledger and gets a receipt, a
held one opens an approval. It also reads `ingest.results.v1` (imports) and `ingest.normalized.v1`
(`NormalizedEntities`). Offsets are committed after the handler, and every target is idempotent.
`journal.anchors.v1` is not read yet: it carries no tenant id, and the journal needs one for RLS.

## Tests

Tests start an embedded PostgreSQL and connect as an ordinary role, because a superuser bypasses RLS.
Docker is not needed.

A normal test run stops that PostgreSQL itself. A test JVM killed by force skips shutdown hooks and leaves it
running, and PIT does this to its workers. To clean up on Windows, in PowerShell:

    Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" |
        Where-Object { $_.ExecutablePath -like '*\embedded-pg\*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

The API contract `schemas/openapi/core.yaml` is exported from the code. After an API change, regenerate it:

    ./gradlew :core:test --tests "*OpenApiContractTest" -Popenapi.update=true
