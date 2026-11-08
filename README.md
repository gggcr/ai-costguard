# AI CostGuard

A single platform that controls a company's digital spending: it decides on each paid AI agent
operation before the money is spent, and turns invoices, exports and access logs into findings with
estimated savings.

Architecture: [Miro board](https://miro.com/app/board/uXjVES7uH9I=/).

## Java part of the monorepo

| Path | Gradle project | Purpose |
|---|---|---|
| `apps/backend/gateway` | `:gateway` | Agent gateway (Spring Cloud Gateway): checks, budget reserve, proxy to providers |
| `apps/backend/core` | `:core` | Core modular monolith (Spring Modulith, PostgreSQL) |
| `libs/java/platform-starter` | `:platform-starter` | Shared starter: tenant and RLS, RFC 9457 errors, tracing |
| `libs/java/events` | `:events` | Event classes generated from `schemas/avro` |
| `schemas/avro` | | Kafka contracts. The schema comes first, classes are generated |

Library versions live only in `gradle/libs.versions.toml`.

## Где что лежит

The rest of the monorepo, accepted as it is:

| Path | What it is |
|---|---|
| `apps/python/ingest` | Ingest pipeline: reads uploaded invoices, exports and bank statements (1C, MT940, CAMT.053, OFX), scans through OCR and text invoices through LLM extraction; suggests the column mapping, writes FOCUS records to `cost.records.v1` |
| `apps/python/anomaly` | Anomaly detector: spend spikes and double charges in `cost.records.v1`, reported to `findings.v1` |
| `apps/python/notary` | NEAR notary: anchors Merkle roots of journal receipts, registers mandates and confirms large operations in `contracts/notary` |
| `apps/python/connectors` | Connectors to 1C, МойСклад, Bitrix24, Яндекс 360, Jamf, Intune, Google Workspace, Okta, Entra ID, Merge, Т-Банк and a mailbox; also run as an agent inside the client's network |
| `apps/python/reports` | Spend forecasts and XLSX/PDF reports |
| `contracts/notary` | The NEAR smart contract (Rust): Merkle roots of receipt batches, mandate hashes, operation confirmations. No tenant on chain ([ADR 0006](docs/adr/0006-notary-near.md)) |
| `deploy/helm`, `deploy/argocd`, `deploy/terraform` | Kubernetes: the Helm chart, Argo CD applications for staging and prod, Yandex Cloud in Terraform |
| `libs/python/costguard-sdk`, `libs/js/costguard-sdk` | SDKs for agents: ask before spending, settle what was spent, wait for a human approval |
| `libs/python/costguard-common` | Shared code of the Python services: Kafka wire format, Schema Registry, settings |
| `apps/frontend` | The web interface (React, Vite) |
| `schemas/` | Every contract: Avro (`schemas/avro`, primary), OpenAPI exported from the code (`schemas/openapi`), ClickHouse |
| `docs/adr`, `docs/runbooks` | Architecture decisions and runbooks |

- The audit engine is a part of the core (`ai.costguard.core.findings`), not a separate service as on the board:
  the rules need the core's own data. See [ADR 0005](docs/adr/0005-audit-engine-in-core.md).
- S3 is SeaweedFS instead of MinIO: MinIO no longer publishes images. SeaweedFS serves the same S3 API on the same
  port, so nothing but `infra/local/docker-compose.yml` knows the difference.

## Build and run

Requires JDK 25 and Docker.

```bash
cp .env.example .env
make up                 # PostgreSQL, Redis, Kafka, Schema Registry, ClickHouse, S3 (SeaweedFS), Keycloak, Vault
./gradlew build
./gradlew :core:bootRun
./gradlew :gateway:bootRun
```


The application connects as an ordinary role, because a superuser bypasses row-level security, and the core
refuses to start otherwise. On an existing database, run `infra/local/postgres/fix-app-role.sql` as an
administrator; the first lines of that file show the command.

On Windows without `make`, run `docker compose -f infra/local/docker-compose.yml --env-file .env up -d --wait`.

**Local development only:** `infra/keycloak/costguard-users-0.json` holds the demo user `demo` with the password
`demo` (the tenant ООО «Ромашка» of `tools/seed`). Only the local compose stack imports it. The Helm chart, the
images and staging and prod do not carry it, and `tools/tests/test_tools.py` checks that they never will.
Staging and prod use their own Keycloak with real users.

