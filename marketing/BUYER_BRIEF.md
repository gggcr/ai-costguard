# Buyer brief

A two-page summary for someone considering acquiring or licensing AI CostGuard. Everything here can be checked in
the repository; nothing is projected or invented.

## What you would get

- **Source code** of a working multi-tenant product: a Java 25 / Spring Boot core, an agent gateway, five Python
  services, a React web UI, Python and TypeScript SDKs, and a Rust smart contract.
- **75 architecture decision records** explaining why each part is built the way it is.
- **Contracts and tests**: Avro schemas for every Kafka topic, OpenAPI specs checked against the code, about
  1,100 automated tests across Java, Python, TypeScript and Rust.
- **Operations**: Docker Compose for local runs, a Helm chart, Argo CD applications, Terraform (Yandex Cloud),
  Prometheus alerts, dashboards, backups with tested restores, 26 runbooks.
- **Documentation in English** (`docs/`) and these buyer materials.

## What it does (implemented)

1. **AI agent governance**: a gateway in front of OpenAI, Anthropic, GigaChat, YandexGPT and paid data APIs;
   per-agent virtual keys; mandates (models, vendors, amounts, hours); policies; budgets enforced at agent,
   department and company level before the call; loop detection; risk scoring; human approval above a threshold;
   an append-only ledger.
2. **Tamper evidence**: a hash-chained journal with Ed25519-signed receipts, Merkle roots anchored in a NEAR
   contract (testnet transactions linked in the README).
3. **SaaS, license and hardware audit**: imports of invoices, bank statements, cloud FOCUS exports and sign-in
   logs; connectors (Microsoft 365/Entra ID, Google Workspace, Okta, Jamf, Intune, Merge, and Russian systems);
   16 audit rules in the core plus spike and double-charge detection, each finding with a savings estimate and a
   workflow; savings confirmed by the next invoice.
4. **Employee cabinet**: license requests from a company pool, purchase requests checked against existing
   subscriptions, documents, vacations, payroll dates, simple electronic signatures.
5. **Platform**: self-registration with a trial, plans and limits, personal-data retention and erasure, activity
   log, SCIM server, notifications by e-mail and Telegram, RU/EN.

## What it does not do yet

| Gap | Effort estimate (engineering judgement, not a quote) |
|---|---|
| Card payments for plans (Stripe): the provider interface exists, only a test provider is implemented | 1–2 weeks |
| AWS / GCP / Azure Terraform (the Helm chart is cloud-neutral) | 1–2 weeks |
| Country rules outside Russia: vacation allowance, payroll frequency, e-signature wording | 1–2 weeks |
| Some employee-cabinet notices and PDF documents are still in Russian | 3–5 days |
| JWT audience validation, general API rate limiting at the core | 1–2 days |
| SOC 2 / ISO 27001, external penetration test, GDPR documents reviewed by a lawyer | outside engineering |

## Risks to weigh

- **No customers and no revenue.** The product was built from 2026-11-02, starting at a NEAR hackathon.
- **Most code was written with AI coding assistants** under the team's direction and review (disclosed in the
  README). Quality is backed by the tests and ADRs, but a buyer should budget for their own review.
- **License and ownership.** The repository is under the MIT license, copyright "AI CostGuard contributors". MIT
  lets anyone who already has a copy use it, so a purchase buys the copyright, the history and the team's know-how,
  not exclusivity over copies already distributed. The seller should provide written assignment from every
  contributor. See `docs/COMMERCIAL_READINESS_REPORT.md` → Licensing. This is not legal advice.
- **Heavy stack** for a small team to operate: PostgreSQL, Kafka, ClickHouse, Redis, Keycloak, S3. `make demo-lite`
  shows a lighter mode is possible.
- **Origin.** Built for the Russian market first. Selling or hosting for Western customers needs a non-Russian legal
  entity, sanctions and export-control advice, and Western cloud and payment accounts.

## How to evaluate in an hour

1. `make demo`, sign in as `demo-us` / `demo` ([docs/INSTALLATION.md](../docs/INSTALLATION.md)).
2. Follow [DEMO_SCRIPT.md](DEMO_SCRIPT.md).
3. Run the tests ([docs/INSTALLATION.md](../docs/INSTALLATION.md#running-the-tests)).
4. Read ADR 0002 (tenant isolation), 0004 (gateway), 0006 (notary), 0072–0074 (Western readiness).
