# AI CostGuard

## Your AI agents spend money on their own. Make them ask first.

AI CostGuard sits between your agents and every paid API they call. Each call is checked against a budget, a
mandate and your rules **before** it is paid, and every decision lands in a journal you can prove was never
edited. The same system finds the software seats, licenses and laptops you pay for and nobody uses.

---

### Stop overspend before it happens

- **Budgets that bite.** Limits per agent, team and company, reserved atomically before the call. When a model
  call would not fit, the gateway asks for fewer output tokens instead of failing.
- **Mandates.** Which models and vendors an agent may use, how much per operation, during which hours.
- **A person decides the big ones.** Above your threshold, the operation waits for an approver in the web console.
- **Loops and anomalies.** Repeated operations and unusual spend slow down, require approval or stop the agent.

### Drop-in for the SDKs you already use

Point the OpenAI or Anthropic SDK at the gateway and swap the provider key for an agent's virtual key. Paid data
APIs and anything else go through a simple authorize → settle API, with Python and TypeScript SDKs.

### A record nobody can quietly rewrite

Every decision is hash-chained and signed. Batches are anchored on a public chain, so an auditor can verify a
receipt without trusting you or us. Only hashes leave your company.

### Find the waste in what you already pay

Upload invoices, card and bank statements, or cloud cost exports; connect Microsoft 365, Google Workspace, Okta,
Jamf or Intune. CostGuard finds:

- licenses of people who left, seats nobody uses, paid seats nobody holds;
- the same product bought twice, overlapping tools, oversized plans, monthly billing where annual is cheaper;
- contracts about to renew themselves, duplicate invoices, invoices above the contract;
- shadow subscriptions paid outside the registry, AI spend that bypasses the gateway;
- laptops not returned by leavers.

Each finding shows the monthly saving in your currency, an owner and a next step — and is confirmed when the next
invoice proves it.

### Built for finance, IT and security

- Roles for owners, finance, IT admins, agent owners, auditors and employees.
- Company data isolated by the database itself (PostgreSQL row-level security).
- Personal-data retention and erasure, an activity log, SCIM provisioning, single sign-on through OIDC.
- Self-hosted on Kubernetes with one Helm chart, or locally with Docker Compose.

---

**Status:** a new product, evaluated with synthetic data; no production customers yet. Card billing, AWS
templates and SOC 2 are on the roadmap. See the [buyer brief](BUYER_BRIEF.md) for exactly what exists today.

**Try it:** `make demo`, then sign in as `demo-us` / `demo` — [installation guide](../docs/INSTALLATION.md).
