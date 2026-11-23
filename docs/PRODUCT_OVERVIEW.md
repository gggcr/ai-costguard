# AI CostGuard — product overview

AI CostGuard keeps a company's digital spend under control in one place: what its AI agents pay for through APIs,
and what it pays for software subscriptions, licenses and employee hardware.

## The problems it solves

1. **AI agents spend money on their own.** An agent that calls GPT, Claude or a paid data API can loop, pick an
   expensive model or overspend a budget, and the company finds out from the provider's invoice at the end of the
   month.
2. **SaaS waste is invisible.** Companies pay for seats nobody uses, for licenses of people who left, for two tools
   that do the same job, for monthly billing where annual is cheaper, and for "shadow" subscriptions paid by card
   that nobody registered.
3. **No trustworthy record.** When something goes wrong, the only record of who allowed a payment is usually an
   internal database that its operator can edit.

## How it works

### Track A — AI agent governance (the gateway)

Every paid call of an agent goes through the CostGuard gateway **before** it is paid. The gateway is a proxy in
front of the providers' own APIs (`/v1/providers/{openai|anthropic|gigachat|yandex}/...`, so standard SDKs work by
changing the base URL and the key), a proxy for paid data APIs (`/v1/egress/<name>/...`), and an
authorize/settle API for anything else the agent pays for. It runs a fixed check ladder:

1. **Identity and plan** — the agent's virtual key, a suspended agent, the company's plan limits.
2. **Mandate and policies** — which models and vendors the agent may use, the maximum per operation, working
   hours, and company-wide policy rules (CEL expressions).
3. **Loops** — the same operation id or the same prompt repeated.
4. **Risk** — the anomaly detector's verdict for the agent or the task (slow down, require approval, stop).
5. **Budget** — a reservation at every level (agent → department → company), atomic in Redis; a model call that
   does not fit is retried with fewer output tokens.
6. **Task fit** — optionally, an LLM assessor checks that the operation matches the stated task.
7. **Human approval** — operations above the mandate's threshold wait for a person.

The provider is paid only if every step passes; the actual cost from the provider's answer is settled, the rest of
the reserve is released. Every decision is recorded as an operation (Kafka → ClickHouse) and posted to an
append-only ledger.

**Tamper evidence.** Each decision is written to a hash-chained journal with Ed25519-signed receipts. Batches of receipts are anchored
as Merkle roots in a NEAR smart contract, together with the hash of every published mandate version and of every
large approved payment. Anyone can verify a receipt against the chain without trusting CostGuard. Only hashes go
on chain, never company or personal data.

### Track B — software, licenses and hardware (the audit)

CostGuard reads invoices, exports and bank statements (Excel/CSV, PDF, scans via OCR, LLM extraction, 1C,
MT940, CAMT.053, OFX, FOCUS exports of AWS/Azure/GCP), sign-in logs (Microsoft 365, Google Workspace, Okta, Entra
ID) and device inventories (Jamf, Intune), and finds overspend by itself. Implemented audit rules include:

- licenses of dismissed employees; licenses unused for N days; paid seats nobody holds;
- two subscriptions to the same product; overlapping tools that do the same job;
- contracts about to renew automatically; oversized plans; monthly billing where annual is cheaper;
- duplicate invoices; invoices above what the subscriptions cost;
- shadow SaaS paid outside the registry; AI spend that bypasses the gateway;
- hardware not returned by leavers, overdue returns, idle hardware in stock, duplicate devices per person.

Every finding carries an estimated monthly saving in the company's currency, an owner and a workflow (accept,
postpone, reject, create a task). The saving is confirmed automatically when the next invoice shows it.

### The employee cabinet

Employees request licenses (served from the company's license pool first), report purchases, see their own
documents, vacations and pay dates, and sign documents with a simple electronic signature (TOTP or PIN). Agent
owners see a traffic light of their agents' budgets and confirm or block held operations.

## Who it is for

- **CFOs and finance teams** of companies from roughly 50 to a few thousand employees that pay for many SaaS tools
  and have started to use AI agents.
- **IT and security leads** who need to know which agents spend what, under which rules, and who approved it.
- **Founders of AI-heavy startups** whose LLM bill grows faster than their revenue.

## What is implemented and what is not

Implemented and covered by automated tests (see [COMMERCIAL_READINESS_REPORT.md](COMMERCIAL_READINESS_REPORT.md)):
the gateway and its check ladder, budgets and the ledger, mandates and policies, human approvals, the journal and
NEAR notary contract, the audit rules above, imports and the ingest pipeline, connectors listed above, the employee
cabinet, multi-tenant isolation with PostgreSQL row-level security, personal-data retention and erasure,
self-registration with a trial, money in the company's currency, the company's time zone and language.

Not implemented yet, or not production-ready:

- **Payments for CostGuard plans**: the payment-provider interface exists, but only a test provider is
  implemented; Stripe is planned.
- **Hosting outside Russia**: Terraform exists for Yandex Cloud only; the Helm chart is cloud-neutral, an AWS module
  is planned.
- **English**: the UI has an RU/EN switch and the core writes notifications and findings in English for English
  companies; the signature stamp and the PDF documents are still Russian.
- **Country rules**: vacation allowance (28 days) and payroll schedule follow Russian labor law.
- **Compliance**: no SOC 2 report; GDPR/CCPA documents are drafts for a lawyer (`docs/privacy`).

## Evaluating it

See [INSTALLATION.md](INSTALLATION.md) for a local run with a synthetic demo company and
[../marketing/DEMO_SCRIPT.md](../marketing/DEMO_SCRIPT.md) for a 15-minute walkthrough. All demo data is
synthetic.
