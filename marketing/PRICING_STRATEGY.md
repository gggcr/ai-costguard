# Pricing strategy

Two different things can be priced: **the product as an asset** (the code, the history and the know-how, sold to
a buyer who will run or develop it) and **the product as a service** (what that buyer would later charge its own
customers). The target of USD 2,000–3,000 is an asset price. Nothing below is market research; it is reasoning
from what exists in the repository.

## 1. Selling the asset (target USD 2,000–3,000)

### What supports the price

- A working, tested codebase of real breadth (core, gateway, five services, UI, SDKs, contract) that would take a
  small team months to rebuild.
- Unusual engineering hygiene for its age: 75 ADRs, contract tests, OpenAPI specs checked by tests, security
  review fixes, runbooks, Helm and CI.
- A demonstrable niche: enforcement of AI agent spend before payment, with a verifiable journal.

### What pulls it down

- No customers, revenue or users: the buyer pays for code, not a business.
- Earlier public versions were MIT: copies obtained before 2026-11-23 stay usable by anyone (IP checklist).
- A heavy stack to operate, Russian-market origins, and gaps listed in the buyer brief.

### Suggested packages

| Package | What the buyer gets | Price idea |
|---|---|---|
| Code only | repository with history, assignment of copyright from all contributors, documentation | USD 2,000 |
| Code and handover | the above, plus a recorded walkthrough and up to 10 hours of Q&A / setup help in the first month | USD 3,000 |
| Code, handover and a gap | the above, plus one item from the buyer brief's gap list (for example Stripe billing) delivered | quoted separately |

Where to list: code and micro-SaaS marketplaces that accept pre-revenue projects (for example Acquire.com,
Flippa, or developer communities). Check each marketplace's rules for pre-revenue listings and for the seller's
jurisdiction before listing.

## 2. If the buyer sells it as a service

Today the plans in `application.yaml` are priced in roubles (Start 4,990 RUB/month, Business 19,990 RUB/month)
and limited by agents, employees, connected systems and operations. For Western customers a buyer could start
from:

| Plan | Limits (as implemented) | Monthly price idea (to validate with customers) |
|---|---|---|
| Trial | 3 agents, 25 employees, 2 systems, 10k operations, 14 days | free |
| Start | 10 agents, 100 employees, 5 systems, 100k operations | USD 99–199 |
| Business | 50 agents, 1,000 employees, 25 systems, 1M operations | USD 499–999 |

The plan catalog takes a currency per plan, so USD or EUR prices are a configuration change
(`costguard.plans.catalog.*`); charging cards needs the Stripe provider, which is not built yet.

The anchor for a customer is the money found: a finding shows its monthly saving, so a plan that costs less than
one or two findings a month is easy to justify — but that has to be shown with the customer's own data, not the
demo's.
