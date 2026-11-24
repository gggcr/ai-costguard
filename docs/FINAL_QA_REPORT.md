# Final QA report — 1.0.0-rc.1

Date: 2026-11-23/24 (UTC+3). Machine: Windows 11, 12 CPUs, 16 GB, Docker Desktop 29.9 (8 GB for Docker),
JDK 25.0.5, Python 3.14, Node 24. Every number below was produced on this machine tonight; nothing is copied
from earlier runs.

## Automated tests

| Suite | Run | Passed | Failed | Skipped | Notes |
|---|---|---|---|---|---|
| Java core (unit, integration on embedded PostgreSQL, OpenAPI contract, S3 test with Docker) | full `gradle test`, Docker tests included, on the final code | 540 | 0 | — | last run 23:43 after the mandate-hours change (the run at 23:28 had 539/0) |
| Java gateway (incl. 27 Testcontainers tests: GatewayFlowIT 14, BudgetReserveIT 9, OperationQuotaIT 3, OperationLogBufferIT 1) | same run | 97 | 0 | 0 | |
| Java platform-starter | same run | 22 | 0 | 0 | |
| Python (all services, contracts, tools, schemas) | `pytest` from the root, helm on PATH | 489 | 0 | 18 | skipped: 4 e2e and 3 smoke (need a stack or staging; e2e run separately below), 1 ClickHouse and 2 backup tests (need a ClickHouse URL, `pg_dump`, `age`), others by their own conditions |
| Python lint | `ruff check .` | clean | | | |
| Web client | `tsc --noEmit`, `vitest run`, `npm run build` | 45 | 0 | 0 | build passes |
| End to end on the Docker stack (`tests/e2e`) | `E2E=1 pytest tests/e2e` | 4 | 0 | 0 | audit, gateway spend with receipt, Excel import through S3 and ingest, employee lifecycle |
| Demo walkthrough (`tools/demo/check_demo.py`) | on a clean stack (volumes removed), final code, 23:38–23:42 | 23 steps | 0 | 0 | stack healthy 2 minutes after `up` including image rebuilds; see [DEMO_VALIDATION_REPORT.md](DEMO_VALIDATION_REPORT.md) |

New tests written tonight, each with a negative control where it guards security: `JwtValidationIntegrationTest`
(14; two fail without the audience check), `StripeBillingIntegrationTest` (11; fails without the signature check or
the event dedup), `SignatureStampTest` (2), `HandoverActPdfTest` (2), `ProductNameTest` (2), export tests in
`EmployeeErasureIntegrationTest` (2), `MandateHoursTest` (1), the English report test (1), residency test updated.

## Static checks and scans

| Check | Result |
|---|---|
| OSV, 281 Java runtime libraries | 0 advisories |
| `npm audit` | 0 |
| `pip-audit`, 86 pinned packages | 0 |
| Secret patterns in all commits, tracked key files | none |
| `helm lint`, `helm template` (default, staging, prod, AWS region with service account) | pass |
| `terraform fmt -check`, `terraform validate` (Yandex and AWS modules) | pass |
| checkov on `deploy/terraform/aws` | 232 passed, 30 failed — all reviewed and accepted (SECURITY_RELEASE_AUDIT.md) |
| SBOM | 375 components, 0 without a declared license |

## Manual checks

- Chrome: sign-in as `demo-us` through Keycloak, English UI, Findings with dollar savings, Plan & billing with
  USD prices, Analytics; no CSP violations after the header change.
- `curl` of the web client's headers on `/`, `/assets/*`, `/settings/plan`.
- Restart of the whole stack: data kept, walkthrough and e2e pass again.

## Not executed (and why)

| What | Why | Classification |
|---|---|---|
| Stripe against a real account (test mode) | no Stripe credentials | external credentials |
| `terraform plan/apply` on AWS | no AWS account | external credentials |
| Staging smoke tests (`tests/smoke`) | no staging environment | external environment |
| Rust NEAR contract tests | no `cargo` on the machine | environment |
| Backup/restore tests | need `pg_dump`, `pg_restore`, `age` | environment |
| `tools/release/verify.sh` as one script | no `uv` on the machine; each of its steps was run individually | environment |
| Load test (`load.yml`) | not run tonight | not attempted |
| Independent penetration test | needs a third party | external |

No test was deleted, skipped by hand, or weakened tonight. One test was changed: `test_residency` used
`eu-west-1` as an *unknown* region; it is now a supported region, so the test uses `ap-south-1` and also checks the
endpoint of `eu-central-1`.

## Re-run before sale — 2026-11-24/25

Same machine, JDK 25.0.5.1 (Temurin), Python 3.12 through `uv`, Node 26, Docker Desktop 29.9. Every number below was
produced in this run, after the changes of 2026-11-24/25 (ADR 0080, V54–V56, the fixes in CHANGELOG.md).

| Suite | Passed | Failed | Skipped | Notes |
|---|---|---|---|---|
| Java core, every test incl. Docker (`S3FileStoreTest`) | 556 | 0 | 0 | the shadow-spend change was re-run separately afterwards: 5/5 |
| Java gateway, incl. Testcontainers (GatewayFlowIT 14, BudgetReserveIT 9, OperationQuotaIT 3, OperationLogBufferIT 1) | 105 | 0 | 0 | |
| Java platform-starter | 22 | 0 | 0 | |
| Python, the whole workspace (`uv run pytest`), helm on PATH | 514 | 0 | 18 | skipped need ClickHouse, Tesseract, the running stack (e2e), staging, or `pg_dump`+`age` |
| `ruff check .` | clean | | | |
| Web client: `tsc -b`, `vitest run`, `npm run build`, image build | 52 | 0 | 0 | |
| Rust NEAR contract in `rust:1.95` (fmt, clippy `-D warnings`, test, wasm build) | 20 | 0 | 0 | previously never run |
| Demo walkthrough on the lite stack without Kafka (`make demo-lite`, `check_demo.py`) | 23 steps | 0 | 0 | twice, the second after recreating core and gateway |
| Backup role dump and restore (`pg_dump --enable-row-security` as `costguard_backup`, `pg_restore` into an empty database, postgres:17) | pass | | | reproduced the CI failure on c473617 first, then the fix (V56) |
| Load test (`tests/load`, k6 → gateway → Redis/Kafka), hard limit 500 000 | see below | | | previously never run |

Load test, gateway jar on the Windows host, Redis, Kafka and the Schema Registry in Docker, k6 in Docker through
`host.docker.internal`:

| Rate | Duration | Achieved | p99 authorize | Unexpected answers | Settled vs limit (Redis check) |
|---|---|---|---|---|---|
| 300/s | 30 s | 300/s | 12.6 ms | 0 | within |
| 1 000/s | 30 s | 999/s | 39.1 ms | 0 | within |
| 2 000/s | 60 s | 1 134/s, 51 278 dropped | 3.33 s (threshold 250 ms: **failed**) | 0 | 463 551.53 of 500 000; Redis spent = k6 settled, reserved 0: **the hard limit held** |

The money is right at every rate; the latency threshold holds up to at least 1 000 operations a second here. Above
that this setup saturates; whether the limit is Docker Desktop's port forwarding on Windows or the gateway is not
told apart here. The `load` workflow runs 2 000/s on a Linux runner and is the number to quote.

Static checks of this run: npm audit (production) 0, pip-audit over `uv.lock` 0, Trivy on the core, gateway and web
client images 0 fixable high or critical (the web client had 68 before its base image moved to nginx 1.31 with
`apk upgrade`), `helm lint` and `helm template` for staging and prod pass, generated API types match the
specification. Terraform: `fmt` passes for both modules and `validate` passed for AWS; the Yandex module could not
be initialised because the Terraform registry did not answer from this network, CI's `terraform` job checks it.

Not run: `tools/release/verify.sh` as one script (Git Bash here cannot start the JDK; the new `release-gate` workflow
runs it in CI), Stripe and AWS against real accounts, staging smoke, the penetration test.
