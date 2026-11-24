# Configuration

Every service reads its settings from environment variables. [`.env.example`](../.env.example) lists all of them
with a comment each; copy it to `.env` for the local stack. In Kubernetes the same variables come from the Helm
values and from secrets ([DEPLOYMENT.md](DEPLOYMENT.md), [runbooks/secrets.md](runbooks/secrets.md)).

Empty optional settings switch a feature off; they never fall back to an insecure default.

## Required for any environment

| Variable | Service | Meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | core | PostgreSQL of the core. The user must not be a superuser (superusers bypass row-level security) |
| `DB_ADMIN_USER`, `DB_ADMIN_PASSWORD` | migrations | the owner role Flyway migrates with |
| `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL` | all | Kafka and the Schema Registry; not needed with `EVENTS_TRANSPORT=direct` |
| `EVENTS_TRANSPORT` | core, gateway | `kafka` (default) or `direct`: a small install without Kafka, the Schema Registry and ClickHouse, where the gateway reads its state from the core and posts operations to it (ADR 0080). Direct also needs `CORE_EVENTS_KAFKA_ENABLED=false` on the core |
| `INTERNAL_TOKEN` | core, gateway | with `direct` only: the shared secret of the core's `/internal/**` paths, the same on both, at least 32 characters (`openssl rand -hex 32`) |
| `CORE_INTERNAL_URL` | gateway | with `direct` only: where the gateway reaches the core (default `http://localhost:8080`) |
| `KEYCLOAK_ISSUER_URI` | core, gateway | the OIDC issuer whose tokens the API accepts |
| `JWT_AUDIENCE` | core | the audience an access token must carry (default `costguard-api`, ADR 0076); it cannot be empty |
| `REDIS_HOST`, `REDIS_PORT` | gateway | budget reserves and rate limits |
| `CLICKHOUSE_URL`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`, `CLICKHOUSE_READER_PASSWORD` | core, services | operations and costs |
| `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | core, ingest | uploaded files |

## Secrets to generate for production

| Variable | How | Without it |
|---|---|---|
| `JOURNAL_PRIVATE_KEY`, `JOURNAL_PUBLIC_KEY` | an Ed25519 pair, base64 PKCS#8 and X.509; rotation in [runbooks/secrets.md](runbooks/secrets.md) (ADR 0028) | a key is generated per run: receipts no longer verify after a restart |
| `CONNECTIONS_ENCRYPTION_KEY` | `openssl rand -base64 32`, keep forever | connector secrets stay in the connector agent's environment |
| `ANOMALY_MODEL_KEY` | `openssl rand -base64 32` | models are not saved between restarts |
| `KEYCLOAK_PRIVACY_CLIENT_SECRET` | the secret of the `costguard-privacy` client | erasures do not delete Keycloak accounts |
| `KEYCLOAK_REGISTRATION_CLIENT_SECRET` | the secret of the `costguard-registration` client | self-registration and invitations answer 503 |

Never reuse the local values from `.env.example` outside a developer machine.

## Optional features

| Feature | Variables | ADR |
|---|---|---|
| AI providers behind the gateway | the provider keys of `costguard.gateway.providers` (OpenAI, Anthropic, GigaChat, YandexGPT) | 0004, 0010 |
| Paid data APIs behind the gateway | `<NAME>_API_KEY` per configured API | 0010 |
| AI task-fit assessor | `ASSESSOR_PROVIDER`, `ASSESSOR_MODEL`, `ASSESSOR_TIMEOUT` | 0011 |
| NEAR notary | `NEAR_RPC_URL`, `NEAR_ACCOUNT_ID`, `NEAR_PRIVATE_KEY`, `NEAR_CONTRACT_ID`; `COSTGUARD_NOTARY_CONFIRM_ABOVE` (amount in the company's currency) | 0006, 0009 |
| OCR of scans | `INGEST_OCR_ENABLED`, `INGEST_TESSERACT`, `INGEST_OCR_LANGUAGES` (e.g. `eng` or `rus+eng`) | 0012 |
| LLM invoice extraction | `INGEST_LLM_*` (a virtual key of CostGuard's own agent) | 0013 |
| Connectors | `GOOGLE_WORKSPACE_KEY`, `OKTA_TOKEN`, `ENTRA_CLIENT_SECRET`, `MERGE_*`, `TBANK_TOKEN` | 0014, 0019 |
| Telegram notifications | `TELEGRAM_BOT_TOKEN` | 0031 |
| Personal-data retention | `PRIVACY_RETENTION_ENABLED`, `PRIVACY_RETAIN_*` (ISO-8601 periods) | 0021 |
| Plans and trial | `PLAN_TRIAL_LENGTH`, `PLAN_WARN_BEFORE`, `COSTGUARD_PLANS_CATALOG_*` | 0037 |
| Exchange rates | `RATES_SOURCE` (`cbr` Bank of Russia, default; `ecb` European Central Bank, for Western installations), `RATES_URL`, `CBR_RATES_ENABLED=false` turns loading off | 0072, 0075 |
| Payments for plans | `PAYMENT_PROVIDER` (empty = off; `dummy` = local test provider only; `stripe`) | 0038, 0077 |
| Stripe | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_{START,BUSINESS}_{USD,EUR}`, `STRIPE_AUTOMATIC_TAX`, `PAYMENT_GRACE` (default `P7D`); see [STRIPE_SETUP.md](STRIPE_SETUP.md) | 0077 |

## Per company (in the product, not in the environment)

The country and the currency are chosen at registration (`POST /api/v1/registrations`); the owner can later change
the currency (while nothing is kept in it yet), the time zone and the language in **Company** settings or
`PUT /api/v1/company`:

- **currency** (ISO 4217): all budgets, prices, findings and savings are kept in it (ADR 0072);
- **country**: how numbers and dates in uploaded files are read, and the defaults below;
- **time zone** (IANA, e.g. `America/New_York`): "today", monthly boundaries, morning notices (ADR 0073);
- **language** (`en` or `ru`): notifications, e-mails and finding texts (ADR 0074).
