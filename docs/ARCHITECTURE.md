# Architecture

AI CostGuard is a monorepo of a few services around one modular core. Decisions are recorded as ADRs in
[`docs/adr`](adr) (74 records); this page is the map.

```
            agents (SDKs, any HTTP client)                     people (browser)
                      │ virtual key                               │ OIDC (Keycloak)
                      ▼                                           ▼
   ┌──────────────────────────────┐   state topics    ┌──────────────────────────────┐
   │ Gateway (Spring Cloud Gateway)│◀──────────────────│ Core (Spring Boot, Modulith)  │◀── React UI
   │ check ladder, Redis reserves  │──operations──────▶│ PostgreSQL with RLS           │
   └──────────────┬───────────────┘      Kafka         └──────┬───────────┬───────────┘
                  │ paid calls                                │ outbox    │ inbox
                  ▼                                           ▼           ▲
       OpenAI, Anthropic, GigaChat,           Kafka (Avro, Schema Registry)  ──▶ ClickHouse (operations, costs)
       YandexGPT, paid data APIs                      │            ▲
                                                      ▼            │
                     Python services: ingest pipeline, anomaly detector, NEAR notary, connectors, reports
                                                      │
                                                      ▼
                                       NEAR contract `contracts/notary` (Rust)
```

## Components

| Component | Path | Stack | Role |
|---|---|---|---|
| Core | `apps/backend/core` | Java 25, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL | The system of record: organisation, agents and mandates, budgets and ledger, approvals, journal, catalog, software registry, findings and audit rules, imports, connections, notifications, plans, employee cabinet, privacy |
| Gateway | `apps/backend/gateway` | Spring Cloud Gateway (WebFlux), Redis (Lua) | In the path of every paid agent call; keeps the state it needs in memory from compacted topics |
| Ingest | `apps/python/ingest` | Python 3.12+, confluent-kafka, openpyxl, pdfplumber, Tesseract | Reads files people upload, suggests the column mapping, writes FOCUS cost records and normalized entities |
| Anomaly detector | `apps/python/anomaly` | scikit-learn, river, MLflow | Spend spikes, double charges, agent risk scores |
| Notary | `apps/python/notary` | Python, ed25519, NEAR JSON-RPC | Anchors Merkle roots of journal receipts, registers mandates, confirms large payments |
| Connectors | `apps/python/connectors` | Python, httpx | 1C, МойСклад, Bitrix24, Yandex 360, Microsoft 365/Entra, Google Workspace, Okta, Jamf, Intune, Merge, T-Bank, a mailbox, SCIM |
| Reports | `apps/python/reports` | Python, fpdf2, openpyxl | Forecasts and XLSX/PDF reports |
| Web UI | `apps/frontend` | React 19, Vite, TypeScript | Owner/finance/IT console and the employee cabinet; RU/EN |
| SDKs | `libs/python/costguard-sdk`, `libs/js/costguard-sdk` | Python, TypeScript | Authorize before spending, settle after, wait for an approval |
| Contract | `contracts/notary` | Rust, near-sdk | Receipt batches, mandate hashes, operation confirmations |

## Key properties

- **Multi-tenancy.** Every tenant table has a `tenant_id` and PostgreSQL row-level security (`FORCE ROW LEVEL
  SECURITY`); the core binds the tenant of the token to the transaction (`app.tenant_id`). ClickHouse readers get
  row policies per company (ADR 0002, 0035).
- **Contracts first.** Kafka topics are defined by Avro schemas in `schemas/avro`; Java classes are generated from
  them, and contract tests check every producer and consumer (Java and Python) against the files (ADR 0003, 0018).
  The REST API of the core is exported to `schemas/openapi/core.yaml` and a test fails when it drifts.
- **Outbox and inbox.** The core publishes events from the Spring Modulith outbox in the same transaction as the
  change; consumers are idempotent by key (ADR 0003).
- **The gateway never guesses.** A call without a price, or with a price in a currency without an exchange rate, is
  refused (`price_unknown`); a failing check is `503 check_unavailable`, never a pass.
- **Money.** Amounts are kept in the company's currency; foreign amounts are converted at Bank of Russia rates of
  their day (ADR 0072). Token prices keep nine decimals.
- **Time and language.** "Today", monthly boundaries and morning notifications use the company's time zone
  (ADR 0073); server texts use the company's language (ADR 0074).
- **Personal data.** Retention terms, employee and company erasure across PostgreSQL, ClickHouse, Kafka (tombstones),
  files and Keycloak; an inventory test fails when a new table or topic is not documented (ADR 0021).

## Infrastructure

- Local: `infra/local/docker-compose.yml` (PostgreSQL, Kafka KRaft, Schema Registry, ClickHouse, Redis, Keycloak,
  SeaweedFS as S3, Mailpit; core, gateway and the Python services in the `demo` profile).
- Kubernetes: the Helm chart `deploy/helm/costguard`, Argo CD applications in `deploy/argocd`, Terraform for Yandex
  Cloud in `deploy/terraform/yandex`. Observability: Prometheus alerts, Loki, OpenTelemetry traces (ADR 0026, 0048,
  0050). Backups: encrypted nightly dumps (ADR 0025).
