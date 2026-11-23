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
