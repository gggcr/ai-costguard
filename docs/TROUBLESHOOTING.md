# Troubleshooting

## Local stack

| Symptom | Cause and fix |
|---|---|
| `make demo` stops with containers "unhealthy" or killed | Docker has less than 8 GB of memory. Raise it in Docker Desktop, or use `make demo-lite` |
| Port already in use (5432, 8080, 8081, 8180, 3000, 9000) | another program holds it. Stop it, or set `POSTGRES_PORT` for PostgreSQL; the other ports are fixed in `infra/local/docker-compose.yml` |
| Sign-in loops back to the login page | Keycloak is not ready yet (first start takes about a minute), or the browser is not on `http://localhost:3000`, the only redirect URI of the local client |
| `demo-us` or `ecarter` cannot sign in | Keycloak was created before these users existed: `make down && make demo` |
| The console is empty right after `make demo` | the audit runs within a minute of start; reload. Agent spend appears once an agent spends through the gateway (`tools/demo/check_demo.py` makes some) |
| `make demo-lite`: the gateway logs "Could not read the core's feed" | the core is still starting; the gateway retries every second and logs "the gateway has the state" once it has read it. If it repeats with 401, `INTERNAL_TOKEN` differs between the core and the gateway |
| `make demo-lite`: the operation journal answers 503 "ClickHouse does not answer" | `CORE_CLICKHOUSE_URL` did not reach the core empty. In PowerShell set it to a space, `$env:CORE_CLICKHOUSE_URL=" "` ([INSTALLATION.md](INSTALLATION.md)) |
| `make demo-lite`: an uploaded invoice stays "processing" | parsing files needs the ingest service and Kafka: use `make demo` |
| E-mails are not delivered | locally all mail goes to Mailpit, http://localhost:8025 |
| The UI is in Russian | the language follows the browser on the first visit; switch RU | EN in the top right corner, the choice is remembered |

## Agents and the gateway

| Answer | Meaning |
|---|---|
| `401 invalid_api_key` | the virtual key is wrong or revoked |
| `423 agent_suspended` | the agent is suspended (by a person or by the risk check) |
| `402 budget_exceeded` | the operation does not fit a budget of the agent, its department or the company |
| `402 plan_limit_exceeded`, `402 plan_read_only` | the company's CostGuard plan limit, or an expired plan |
| `403 policy_denied` | the model, vendor, amount or hour is outside the agent's mandate, or a policy forbids it |
| `403 approval_required` (`ApprovalRequired` in the SDK) | the operation waits for a person; poll `GET /v1/spend/approvals/{id}` or call `wait_for_approval`, then repeat it with the same operation id |
| `403 approval_rejected`, `403 approval_expired`, `409 approval_used` | the person said no, nobody answered in time, or the approval was already spent |
| `409 duplicate_operation` | the same operation id was sent again |
| `429 rate_limited` | the agent calls too often, or repeats itself in a loop |
| `403 price_unknown` | the model or API has no price in the catalog, or its currency has no exchange rate yet; add the price in **Catalog** |
| `503 check_unavailable` | a check could not run (Redis, state topics); the gateway fails closed. See [runbooks/gateway.md](runbooks/gateway.md) |

The exact status codes are in [`schemas/openapi/gateway.yaml`](../schemas/openapi/gateway.yaml).

## Imports

| Symptom | Fix |
|---|---|
| Amounts read ten or a thousand times too large or small | the company's country decides how `1,234.56` vs `1.234,56` is read; check the country of the company |
| Dates swapped (day and month) | the same: US files are read as month/day/year, most others as day/month/year |
| A scan is not recognised | OCR needs Tesseract with the right languages (`INGEST_OCR_LANGUAGES=eng`) |

## Operations

Runbooks per component are in [`docs/runbooks`](runbooks): core, gateway, Kafka lag, the outbox, Redis,
ClickHouse access, Keycloak, backups, disks, key leaks, alerting.
