# Commercial readiness report

> **Update 2026-11-24.** This report describes the night of 2026-11-22/23 and is kept as history. The current state
> is in [RELEASE_NOTES.md](RELEASE_NOTES.md) and [FINAL_QA_REPORT.md](FINAL_QA_REPORT.md): JWT audience, Stripe,
> AWS Terraform, data export and the Docker demo validation were done since, and everything was pushed to
> `goidochka` at the owner's request. Current status: **RELEASE CANDIDATE — EXTERNAL BLOCKERS REMAIN**. The
> licensing section below describes the MIT license; since 2026-11-23 the code is "All Rights Reserved" — see
> [INTELLECTUAL_PROPERTY_CHECKLIST.md](INTELLECTUAL_PROPERTY_CHECKLIST.md).

Mission: prepare AI CostGuard for sale to Western buyers (target price USD 2,000–3,000). Night of 2026-11-22/23,
local time UTC+3. Work done autonomously by Claude Code in a separate clone, committed locally, **never pushed**.

## 1. Initial state

- Branch `goidochka` at `1565f07`, which already contained the earlier Western-readiness work done at the user's
  request and pushed then: money in the company's currency and country-aware file parsing (ADR 0072), the company's
  time zone (ADR 0073).
- Uncommitted work in progress: the backend half of the company's language (ADR 0074).
- A mature codebase: 73 ADRs, about 590 Java tests, about 470 Python tests, 43 UI tests, CI with security scans.
  No customers, no revenue, Russian-first UI texts and demo data, Bank of Russia as the only FX source, roubles in
  the plan catalog.

## 2. Architecture overview

See [ARCHITECTURE.md](ARCHITECTURE.md): a Spring Modulith core on PostgreSQL with row-level security, a Spring Cloud
Gateway in the agent's path with Redis reserves, Kafka/Avro contracts, ClickHouse, five Python services, a React
UI, Python/TypeScript SDKs and a NEAR contract in Rust.

## 3. Major problems found

| Priority | Problem | Status |
|---|---|---|
| P1 security | 14 known advisories (9 high) in transitive Java libraries: jackson-core/databind 2.22.1, snappy-java 1.1.10.7, lz4-java 1.11.2, opentelemetry-api 1.62.0. CI's Trivy gate fails only on critical, so these were reported but not blocking | fixed |
| P1 Western | Server texts (notifications, e-mails, Telegram, findings) only in Russian; e-mails and Telegram are not translated by the browser | fixed, except the signature stamp, PDFs and shadow-spend category names |
| P1 Western | UI defaulted to Russian; numbers and dates always in Russian format | fixed |
| P1 Western | Exchange rates only from the Bank of Russia | fixed: ECB source |
| P2 | No English product documentation for a buyer; demo company only Russian | fixed |
| P2 | "Contour A/B" in the English UI | fixed ("Track A/B") |
| P2 | The demo seed could not load into the test helper (dataclass loading) — found while adding the US company | fixed |
| Open | JWT audience not validated (any token of the CostGuard realm is accepted) | documented, not changed: needs a live Keycloak to test safely |
| Open | No Stripe provider, no AWS Terraform, Russian labour rules for vacations/payroll | documented as gaps |

No P0 problem (data loss, tenant leak, auth bypass) was found in the parts reviewed this night.

## 4–5. Fixes and features (commits on `work`, mirrored to branch `productization` in the main copy)

| Commit | What |
|---|---|
| `c6486d6` | ADR 0074: company language; notifications, e-mail, Telegram, findings, agent light, leases in English |
| `496bacd` | UI: browser language on first visit, numbers/dates/money by UI language |
| `1a1e7cd` | security: Jackson 2, OpenTelemetry, snappy-java, lz4-java floors |
| `8111eac` | US demo company Brightline Analytics Inc. (`demo-us` / `demo`, `ecarter` / `demo`) and `DemoSeedTest` |
| `d8234e1` | English docs: overview, architecture, installation, configuration, deployment, security, API, troubleshooting, changelog |
| `b9ee77c` | buyer materials in `marketing/`; "Track A/B" in the UI |
| `2da52a2` | ADR 0075: ECB rates, a rate has a base currency |
| `7c85e47` | license notices of the cabinet in the company's language |
| `3cdc9e3` | vacation, payroll and document notices in the company's language |
| `b072e08` | purchase request answers in the company's language |
| last commit | this report, progress log and checkpoint |

## 6–7. Security

Reviewed: identity and tenant binding, RLS tests, gateway fail-closed behaviour, egress, secrets defaults (no
insecure defaults in `application.yaml`), secret scan of tracked files and history (nothing found), dependency
scans. Details and remaining items: [SECURITY.md](SECURITY.md).

| Scan | Scope | Result |
|---|---|---|
| OSV (api.osv.dev) | 281 resolved runtime libraries of core and gateway | 14 → 0 after upgrades |
| pip-audit | 86 packages pinned in `uv.lock` | 0 |
| npm audit | web UI (all and production) | 0 |
| regex secret scan | tracked files and full history | 0 |

## 8–11. Tests

Run on this machine (Windows, JDK 25 Temurin, Python 3.14, Node 22), without Docker:

| Suite | Command | Result |
|---|---|---|
| Core (Java) | `gradle :core:test` | 502 passed, 0 failed (final run, 05:44, all commits included) |
| Gateway (Java) | `gradle :gateway:test` | 70 passed, 0 failed (final run) |
| platform-starter | `gradle :platform-starter:test` | 22 passed, 0 failed |
| Tools (Python) | `pytest tools/tests` | 41 passed, 22 skipped (helm, terraform and Docker checks) |
| Python services | `pytest --ignore=tests/contracts`; `pytest tests/contracts`; `ruff check .` | 443 passed, 38 skipped; 25 contract tests passed; ruff clean |
| Web UI | `tsc -b`, `vitest run`, `npm run build` | 45 passed, build OK |
| OpenAPI contract | `OpenApiContractTest` | passed (spec regenerated for ADR 0074) |

Not executed (UNVERIFIED): tests that need Docker (`*IT` Testcontainers tests, `S3FileStoreTest`), Rust `cargo test` (no cargo here), Helm and Terraform checks
(no helm/terraform binaries here), `make demo` end to end, and the demo script. GitHub Actions were not triggered
(nothing was pushed).

## 12–14. Installation, deployment, UX

- Installation is documented for a local evaluation ([INSTALLATION.md](INSTALLATION.md)); not run end to end
  tonight (Docker not used).
- Deployment: Helm chart and Argo CD are documented; Terraform exists only for Yandex Cloud. **Not deployment-ready
  for AWS/GCP/Azure** without new infrastructure code.
- UX: English by default for non-Russian browsers, English number/date/money formats, a US demo company, English
  server texts for English companies. Cabinet notices about licenses, vacations, payroll, documents and purchases are now English too.
  Remaining Russian: the signature stamp, the PDF handover acts and shadow-spend category names.

## 15–17. Documentation and commercial materials

`docs/`: PRODUCT_OVERVIEW, ARCHITECTURE, INSTALLATION, CONFIGURATION, DEPLOYMENT, SECURITY, TROUBLESHOOTING, API,
CHANGELOG. `marketing/`: PRODUCT_POSITIONING, SALES_PAGE, BUYER_BRIEF, DEMO_SCRIPT, FAQ, PRICING_STRATEGY. Claims
were checked against the code; no customers, results or certifications were invented; demo data is labelled
synthetic. Runbooks and ADRs remain in Russian.

## 18. Licensing (not legal advice)

- **Project license: MIT**, "Copyright (c) 2026 AI CostGuard contributors". Two Git authors appear in the history.
  MIT lets anyone who has a copy keep using it; a sale transfers copyright and know-how, not exclusivity over copies
  already distributed. A buyer will want a written copyright assignment from every contributor. If the repository
  has ever been public, assume copies exist.
- **Libraries**: web UI production dependencies are MIT; Python packages in the evaluated environment are
  permissive except **fpdf2 (LGPL-3.0)** in the reports service, which is fine to use as an unmodified library but
  must keep its notice and allow replacement. Java runtime libraries are Apache-2.0/MIT/BSD family (Spring,
  Jackson, Flyway, pgjdbc, PDFBox, CEL); not individually audited.
- **Infrastructure images** (run as separate services, not bundled into CostGuard's code), worth a buyer's
  attention if they plan a hosted offering: Redis 7.4 (RSALv2/SSPLv1, not OSI-approved; Valkey is a drop-in
  alternative), HashiCorp Vault 1.18 (BSL 1.1), Confluent Schema Registry (Confluent Community License), Grafana and
  Tempo (AGPL-3.0, unmodified). None prevents self-hosting CostGuard; some restrict offering those components
  themselves as a service.

## 19–21. Limitations, risks, next steps

1. Stripe provider for plan payments (interface exists) and USD/EUR plan prices.
2. AWS Terraform module (EKS, RDS, MSK, ElastiCache, S3) for US and EU regions.
3. Remaining English texts in the cabinet and PDFs; country rules for vacations and payroll.
4. JWT audience validation and an API rate limit at the ingress.
5. Rehearse `make demo` and the demo script; run the Docker-based tests.
6. GDPR/CCPA documents reviewed by a lawyer; a non-Russian legal entity; sanctions and export-control advice.
7. An external penetration test before any production customer.

## 22–25. Git

- Branch: `work` in the mission clone; mirrored as branch **`productization`** in `C:\Users\Kirill\ai-costguard`
  (the main copy's own branch `feat/scim` and its uncommitted change were not touched).
- Commits: listed in §4–5 and `git log 1565f07..productization`.
- Final status: see the checkpoint file.
- **No `git push` was performed** during this mission.

## 26–27. Interruptions

The usage limit was reached shortly after 02:15 and lifted at about 05:10; close to three hours were lost. Automatic
resumption was configured with session-only scheduled prompts (CronCreate) and a checkpoint file; after the reset
the session continued from the repository state. Several queued resumption prompts arrived at once when the limit
was lifted.

## 28. Verdict

**TECHNICALLY IMPROVED BUT NOT YET COMMERCIALLY READY**

The product is real, broad and well tested, and it is now presentable to an English-speaking evaluator. But the
local demo was not run end to end tonight, card billing and Western cloud infrastructure do not exist, some texts
(signature stamp, PDFs) are still Russian, and the ownership and licensing questions have to be settled before a sale. With a rehearsed
demo and the contributor assignment in hand, it would be fit for limited buyer demonstrations.
