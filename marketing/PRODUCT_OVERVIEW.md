# CostGuard — product overview

**CostGuard puts a spending check in front of your AI agents and finds the money your company already wastes on
software and equipment.**

## The problem

1. **AI agents now spend money on their own.** They call paid models and APIs, buy reports, retry in loops. Finance
   learns about it from the invoice, after the fact, with no idea which agent, task or team caused it.
2. **SaaS waste is invisible.** Seats nobody uses, licenses still held by people who left, the same tool bought
   twice, contracts that renew themselves. The data exists in your identity provider, your invoices and your HR
   system, but nobody joins it up.
3. **Equipment walks out of the door** when people leave, and nobody notices until an audit.

## What CostGuard does

**Track A — AI agent governance (before the money moves)**
- An API gateway agents call before they spend: identity → mandate → loop detection → risk score → budget →
  task fit → approval. It fails closed: no published mandate, no spend.
- Mandates per agent (what it may buy, up to how much, when), budgets per company, department and agent, approval
  above thresholds, a full operations journal with signed receipts (optionally anchored on a public blockchain).
- Model calls through the same gateway, metered per agent and task.

**Track B — software and equipment spend (what you already pay for)**
- Connects to Microsoft Entra ID, Google Workspace, Okta, Jamf, Intune and others; reads invoices and bank
  statements you upload (CSV, Excel, PDF, scans).
- An audit that runs by itself: unused and idle seats, licenses of leavers, duplicate subscriptions, auto-renewals
  coming up, devices not returned. Each finding has an owner, a workflow and a saving that is confirmed only when it
  shows in the next invoice.
- Employee self-service: license requests served from the company's pool first, purchase requests, devices held.

**Platform**
- Multi-company SaaS with strict tenant isolation (PostgreSQL row-level security), SSO (OIDC, Entra ID, Google),
  roles (owner, finance, IT admin, agent owner, auditor, employee), audit trail, English and Russian interface,
  company currency and time zone, US and EU data regions, subscription billing through Stripe.

## Who it is for

| Segment | Why they buy |
|---|---|
| Companies running AI agents in production (50–2,000 employees) | control and attribution of agent spending before it happens |
| IT and finance teams of SaaS-heavy companies | recover idle seats and stop duplicate tools |
| MSPs and IT service firms | run the audit for several client companies from one install |

## What it is not (yet)

Not a general FinOps tool for cloud infrastructure bills; not an HR system; not certified (no SOC 2 yet); no
production customers yet. See [BUYER_BRIEF.md](BUYER_BRIEF.md) for the honest state.

## See it

A 15-minute scripted demo with a synthetic US company: [DEMO_SCRIPT.md](DEMO_SCRIPT.md).
Pricing: [PRICING.md](PRICING.md). Security: [SECURITY_OVERVIEW.md](SECURITY_OVERVIEW.md).
