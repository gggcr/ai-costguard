# Product positioning

## One line

AI CostGuard stops AI agents from overspending before the money leaves, and finds the SaaS seats, licenses and
hardware a company pays for and does not use.

## The category it sits in

Two categories that are usually bought separately:

- **AI spend governance / LLM gateways** — proxies that meter and limit model calls.
- **SaaS management** — tools that find unused licenses and shadow IT.

CostGuard combines them around one idea: *every unit of digital spend has an owner, a budget and a rule, and the
record of who allowed it cannot be quietly rewritten.*

## Who it is for

| Buyer | Pain | What CostGuard gives them |
|---|---|---|
| CFO / finance lead, 50–2,000 employees | AI and SaaS spend grows, nobody owns it, invoices arrive after the fact | budgets per agent, department and company that are enforced before payment; findings with a saving in the company's currency |
| Head of IT / security | agents call paid APIs with shared keys; no audit trail; leavers keep licenses | virtual keys per agent, mandates, human approval above a threshold, tamper-evident journal, offboarding that revokes access |
| Founder of an AI-heavy startup | the LLM bill is the second-largest cost line and spikes without warning | a drop-in gateway (change the base URL), loop detection, budget-aware token limits |

## Why it is different

1. **Enforcement before payment, not reporting after.** Most cost tools read the invoice; the CostGuard gateway
   sits in the agent's path and refuses or holds the call.
2. **Proof, not just logs.** Decisions are hash-chained and signed; batches are anchored on a public chain (NEAR)
   so a third party can verify a receipt without trusting the operator. Only hashes leave the company.
3. **Agents and SaaS in one ledger.** The same budgets, approvals and findings cover model calls, paid APIs,
   subscriptions and hardware.
4. **Self-hostable and multi-tenant.** Runs on Kubernetes from one Helm chart; tenant isolation is enforced by the
   database (row-level security), not only by application code.
5. **Open architecture.** 74 written architecture decisions, contracts-first events, OpenAPI specs checked by tests.

## What to say carefully

- It is a **new product without production customers yet**. Say "built to", not "used by".
- The NEAR anchoring is optional and works on testnet; mainnet use has not been exercised.
- The first version was built for Russian companies; Western readiness (currency, time zone, language, US demo
  company) was added in November 2026. Stripe billing, AWS Terraform and an ECB rate source are not built yet.
- Savings shown in the demo are computed from **synthetic** data.

## Proof points that are true today

- 74 ADRs; about 590 automated Java tests, about 470 Python tests, 45 UI tests and 20 Rust contract tests (see
  `docs/COMMERCIAL_READINESS_REPORT.md` for the exact runs).
- Security review findings fixed and documented (ADR 0069–0071); dependency scan clean as of 2026-11-23.
- A NEAR testnet contract with real anchored transactions (links in the README).
