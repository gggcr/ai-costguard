# Installation (local evaluation)

This brings up the whole product on one machine with two synthetic demo companies. It is meant for evaluation and
development, not for production (see [DEPLOYMENT.md](DEPLOYMENT.md)).

## Requirements

| Tool | Version | Used for |
|---|---|---|
| Docker Desktop or Docker Engine with Compose v2 | recent | infrastructure and the services |
| Memory for Docker | 8 GB (`make demo`) or 2 GB (`make demo-lite`) | |
| JDK | 25 | building the core and the gateway |
| Python | 3.12+ with [uv](https://docs.astral.sh/uv/) | Python services, seed data, tools |
| Node.js | 22+ | the web UI in development mode |
| GNU make | any | the shortcuts below (on Windows: Git Bash or WSL) |

## Steps

```bash
git clone -b goidochka https://github.com/gggcr/ai-costguard.git
cd ai-costguard
cp .env.example .env          # local defaults; nothing in it is a real secret
make demo                     # 5–10 minutes on the first run: builds the jars and images
```

`make demo` builds the core and the gateway, starts PostgreSQL, Kafka, Schema Registry, ClickHouse, Redis,
Keycloak, SeaweedFS (S3), Mailpit and the services (the core, the gateway, the web client, ingest and the anomaly
detector), creates the ClickHouse tables, and loads the demo data (`tools/seed/seed.py`). Audit findings appear
within a minute. With the stack up, `tests/e2e` (`E2E=1`) and `tests/smoke` (the local settings are in
[runbooks/smoke.md](runbooks/smoke.md)) run against it.

Without make (Windows without WSL, for example), the same steps by hand, from the repository root in Git Bash:

```bash
./gradlew :core:bootJar :gateway:bootJar
docker compose -f infra/local/docker-compose.yml --env-file .env --profile demo up -d --build --wait
CLICKHOUSE_URL=http://localhost:8123 CLICKHOUSE_USER=costguard CLICKHOUSE_PASSWORD=costguard \
  CLICKHOUSE_ACCESS_MANAGEMENT=1 CLICKHOUSE_READER_PASSWORD=costguard-reader KAFKA_BROKERS=kafka:29092 \
  SCHEMA_REGISTRY_URL=http://schema-registry:8081 uv run python schemas/clickhouse/apply.py
uv run python tools/seed/seed.py | docker compose -f infra/local/docker-compose.yml exec -T postgres \
  psql -q -v ON_ERROR_STOP=1 -U postgres -d costguard
uv run python tools/demo/check_demo.py     # optional: walks the demo through the API, PASS/FAIL per step
```

### The lite stack: the whole AI gateway without Kafka

On a smaller machine `make demo-lite` (about 2 GB for Docker; the containers took 1.5 GB on 2026-11-24) runs
PostgreSQL, Redis, Keycloak, Mailpit, S3, the core, the AI gateway and the web UI, with no Kafka, Schema Registry or
ClickHouse: the gateway reads its state from the core and posts its operations to it (`EVENTS_TRANSPORT=direct`,
ADR 0080). Agents, mandates, budgets, approvals, signed receipts, the operation journal, agent spend charts, the
employee cabinet, software and the audit all work, and `tools/demo/check_demo.py` passes. What needs Kafka does not:
parsing uploaded files (ingest), the anomaly detector and the NEAR notary.

Without make, in Git Bash (an empty `CORE_CLICKHOUSE_URL` matters: it tells the core there is no ClickHouse):

```bash
./gradlew :core:bootJar :gateway:bootJar
docker compose -f infra/local/docker-compose.yml --env-file .env up -d --wait postgres redis keycloak mailpit s3
EVENTS_TRANSPORT=direct CORE_EVENTS_KAFKA_ENABLED=false KAFKA_LISTENERS=false CORE_CLICKHOUSE_URL= \
  INTERNAL_TOKEN=local-only-internal-token-change-me-0123456789 CORE_JAVA_OPTS="-Xmx512m -XX:+UseSerialGC" \
  docker compose -f infra/local/docker-compose.yml --env-file .env --profile demo up -d --build --no-deps --wait \
  core gateway frontend
uv run python tools/seed/seed.py | docker compose -f infra/local/docker-compose.yml exec -T postgres \
  psql -q -v ON_ERROR_STOP=1 -U postgres -d costguard
```

In Windows PowerShell set `$env:CORE_CLICKHOUSE_URL=" "` (a space): an empty value there removes the variable, and
the core would wait for a ClickHouse that is not running. Pipe the seed from Git Bash or `cmd`: PowerShell 5.1
re-encodes piped text and breaks the Cyrillic names of the Russian demo company.

## Sign in

Open http://localhost:3000 and choose "Sign in with login and password". All people, companies and amounts are
synthetic.

| Login / password | Company | Role | What you see |
|---|---|---|---|
| `demo-us` / `demo` | Brightline Analytics Inc. (USD, US, English) | owner | the whole console |
| `ecarter` / `demo` | Brightline Analytics Inc. | employee | the employee cabinet only |
| `demo` / `demo` | ООО «Ромашка» (RUB, Russia, Russian) | owner | the whole console |
| `ivanov` / `demo` | ООО «Ромашка» | employee | the employee cabinet only |

The UI starts in the browser's language (English for any non-Russian browser); the RU | EN switch is in the top
right corner.

Keycloak keeps no volume and imports these users each time its container is created. If you ran `make demo` before
this version, run `make down && make demo` once; the seed only adds what is missing.

## Other addresses

| What | Where |
|---|---|
| Web UI | http://localhost:3000 |
| Core API and Swagger UI | http://localhost:8080/swagger-ui.html, spec in `schemas/openapi/core.yaml` |
| Agent gateway | http://localhost:8081 |
| Keycloak admin | http://localhost:8180 (`admin` / `KEYCLOAK_ADMIN_PASSWORD` from `.env`) |
| Mailpit (all e-mail the stack sends) | http://localhost:8025 |
| Health | http://localhost:8080/actuator/health, http://localhost:8081/actuator/health |

## Running the tests

| Part | Command | Docker |
|---|---|---|
| Java (core, gateway, starter) | `./gradlew test` | the `*IT` tests use Testcontainers; the rest uses an embedded PostgreSQL |
| Python services | `uv sync --all-packages && uv run pytest` | contract tests need none |
| Web UI | `cd apps/frontend && npm ci && npx tsc -b && npx vitest run && npm run build` | no |
| NEAR contract | `cd contracts/notary && cargo test` | no |

## The NEAR part without Docker

```bash
uv sync --all-packages
uv run python tools/near/demo.py verify
```

It reads an anchored batch from the testnet contract and shows that a real receipt verifies and a tampered one does
not. See the README for details.
