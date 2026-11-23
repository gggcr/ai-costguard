# Demo validation report

Date: 2026-11-23 (evening, UTC+3). Branch `work` → `goidochka`, from commit `d384350` (Stripe) onwards.

## Environment

| | |
|---|---|
| Host | Windows 11 Pro, 12 logical CPUs, 16 GB RAM |
| Docker | Docker Desktop, engine 29.9.3, Compose v5.7.0; 8 GB and 12 CPUs given to Docker |
| Java | Temurin JDK 25.0.5 (for `./gradlew :core:bootJar :gateway:bootJar`) |
| Python | 3.14 virtual environment with the workspace packages (`uv.lock`) |
| Compose project | `costguard-rc` (a separate project name so the run starts from empty volumes) |

## Commands executed

`make` is not installed on this Windows host, so the steps of `make demo` were run by hand. They are the commands
now documented in [INSTALLATION.md](INSTALLATION.md) under "Without make":

```bash
./gradlew :core:bootJar :gateway:bootJar
docker compose -p costguard-rc -f infra/local/docker-compose.yml --profile demo down -v      # clean start
docker compose -p costguard-rc -f infra/local/docker-compose.yml --profile demo up -d --build --wait
CLICKHOUSE_URL=http://localhost:8123 ... python schemas/clickhouse/apply.py                    # make clickhouse
python tools/seed/seed.py | docker compose ... exec -T postgres psql -q -v ON_ERROR_STOP=1 -U postgres -d costguard
python tools/demo/check_demo.py                                                              # the demo walkthrough
E2E=1 pytest tests/e2e                                                                       # end-to-end suite
docker compose ... stop && docker compose ... up -d --wait                                   # restart
python tools/demo/check_demo.py && E2E=1 pytest tests/e2e                                    # again after restart
```

## Services started

PostgreSQL 17, Redis 7.4, Kafka 3.9 (KRaft), Schema Registry 7.7, ClickHouse 24.8, SeaweedFS (S3), Keycloak 26.0,
Vault 1.18 (dev), Mailpit, topic creation job, the core, the gateway, the ingest service, the web UI. All 14
long-running services reported healthy; the topic job exited 0.

From empty volumes to healthy: **72 seconds** (images already pulled; the first pull took about 30 minutes on this
connection). ClickHouse tables and row policies applied; the seed loaded both demo companies (ООО «Ромашка» and
Brightline Analytics Inc.) without errors. Flyway applied V1–V53 on the empty database at core start.

## Results

| Check | Result |
|---|---|
| `tools/demo/check_demo.py` (the API steps of [DEMO_SCRIPT.md](../marketing/DEMO_SCRIPT.md), as Brightline) | **23 / 23 passed** |
| `tests/e2e` (audit, gateway spend with receipt, Excel import through S3 and the ingest service, employee lifecycle) | **4 / 4 passed** |
| Stop the whole stack, start it again | healthy again in 53 seconds |
| Persistence after restart (mandates, agents, findings, ledger entries) | identical counts before and after (2 / 2 / 23 / 6) |
| `check_demo.py` and `tests/e2e` again after the restart | 23 / 23 and 4 / 4 |
| Errors in the core, gateway and ingest logs during the runs | none (`ERROR` / exceptions: 0) |
| Web UI in Chrome: sign-in as `demo-us` through Keycloak, English UI, Findings, Plan & billing | works; prices in USD, findings in English with dollar savings |

What `check_demo.py` covers, step by step: Keycloak sign-in and the `costguard-api` audience in the token; the
company is Brightline, USD, English; the audit found the seeded waste by itself (11 findings, all titles in
English); a mandate created and published; an agent connected, a `cg_live_` key issued; a $200 agent budget; the
gateway authorizes $40 after learning the state from Kafka; $150 is held for approval (`403 approval_required`);
the owner approves in the core; the same request with the same `Idempotency-Key` passes; another $150 is refused
with `402 budget_exceeded`; the $40 operation is settled and has a signed receipt; the employee `ecarter` opens the
cabinet and gets `403` on company data; a token of another Keycloak client is refused with `401` (ADR 0076); the
web UI and the plan page are served; plans are priced in USD.

## Problems found and fixed

| Problem | Fix |
|---|---|
| The demo script asked for the receipt of an operation that was never settled: there is no receipt before settlement | DEMO_SCRIPT step 3 now settles the operation (`/v1/spend/settle`) |
| The demo script implied the agent's `approvalId` is the approval to decide on; it is the operation the agent polls at the gateway | DEMO_SCRIPT explains it; people decide in **Approvals** (`operationId` links the two) |
| The plan page did not exist, although Stripe and the old payment flow return the payer to `/settings/plan` | Page "Plan & billing" added (commit `d384350`) |
| "Google Google Workspace" in finding titles (vendor repeated when the product name already contains it) | `SoftwareAuditRules.productName`, `ProductNameTest` |
| No way to run the demo on Windows without make | INSTALLATION.md "Without make" |
| The demo script said it was never rehearsed | It now says what was checked and how (this report) |

## Remaining limitations

- The clicks of the demo script in the web UI were spot-checked in Chrome (sign-in, Findings, Plan & billing), not
  scripted. Rehearse the full UI walkthrough once before a customer call.
- The login page says a Google Authenticator code follows the e-mail; for the demo users the password works
  ("Sign in with login and password"). The wording is accurate for companies that set up codes, but it may confuse
  a first-time viewer.
- Mandate hours are Russian words in the API (`круглосуточно`, `только рабочие дни`, or `HH:MM–HH:MM`); the web UI
  shows them in English. Integrators calling the API directly see the Russian values.
- The demo uses `PAYMENT_PROVIDER=dummy`: "Subscribe" succeeds without money. Stripe needs real test keys
  ([STRIPE_SETUP.md](STRIPE_SETUP.md)).
- NEAR anchoring of receipts is not part of this run (it needs a testnet account and key).
- Docker needs about 8 GB; on smaller machines use `make demo-lite` (no gateway).

## Reproducing

1. Docker with 8 GB of memory, JDK 25, Python with `uv`.
2. `cp .env.example .env && make demo` (or the "Without make" commands in INSTALLATION.md).
3. `uv run python tools/demo/check_demo.py` — expect `23 of 23 steps passed`.
4. `E2E=1 uv run pytest tests/e2e` — expect 4 passed.
5. Open http://localhost:3000, sign in as `demo-us` / `demo` (owner) or `ecarter` / `demo` (employee).
6. To start over: `make down` and remove the volumes (`docker compose -f infra/local/docker-compose.yml down -v`
   deletes the local demo data), then `make demo`.
