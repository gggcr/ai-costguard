# Demo script (about 15 minutes)

For a live walkthrough with a prospective buyer. Uses the local stack and the **synthetic** company Brightline
Analytics Inc.; say so at the start. No provider keys are needed: the agent part uses the gateway's
authorize/settle API, which charges budgets without calling a model.

## Before the call (10 minutes)

> Checked on 2026-11-23 on a clean Docker stack: `python tools/demo/check_demo.py` walks every API step below as
> Brightline (23 of 23 passed, again after a restart; see docs/DEMO_VALIDATION_REPORT.md). The clicks in the web
> interface were not recorded by that check: rehearse them once before a call.

```bash
cp .env.example .env && make demo        # docs/INSTALLATION.md
python tools/demo/check_demo.py          # optional: walks the API steps below and prints PASS/FAIL
```

Open http://localhost:3000 in a browser set to English, sign in as `demo-us` / `demo`. Have a terminal ready.

## 1. The problem (1 minute)

"Your agents now pay for model calls and APIs on their own, your SaaS bill has seats nobody uses, and you learn
about both from the invoice. CostGuard puts a check *before* the payment and finds the waste in what you already
pay for."

## 2. Software waste the audit found by itself (4 minutes)

- **Findings** (Track B): the audit ran on its own within a minute of start. Show:
  - a Sales Cloud license still assigned to Robert Brown, who left 20 days ago;
  - seats paid for but not assigned, and licenses unused for weeks;
  - two Jira subscriptions in two departments (the same product bought twice);
  - the Salesforce contract that renews itself in 40 days, with a 30-day notice period;
  - the laptop Robert Brown did not return.
- Open one finding: the saving in dollars, the owner, the history. Press **Accept**: the finding moves to the
  workflow; when the next invoice no longer contains the cost, the saving is confirmed automatically
  (**Savings visible in the invoice**).
- **Subscriptions and contracts**: seats vs. holders; **Employee hardware**: who holds what.

## 3. AI agent governance (6 minutes)

1. **Mandates and rules → New mandate**: allow paid APIs up to $300 per operation, approval above $100; **Publish**
   it, then **Simulate** an operation against it. An agent without a published mandate is refused everything
   (`403 policy_denied`): the gateway fails closed.
2. **AI agents → Connect agent**: name "Research agent", department Engineering, the mandate above, a monthly
   limit of $200. Open it, **Issue key**, copy the `cg_live_…` key.
3. In the terminal, let the agent buy something it is allowed to:

   ```bash
   KEY=cg_live_...   # the key you copied
   curl -s http://localhost:8081/v1/spend/authorize -H "Authorization: Bearer $KEY" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
     -d '{"kind":"paid_api","vendor":"Acme Data","product":"Company report","amount":40,"taskId":"demo-1"}'
   ```

   The answer is an authorization (`authorizationId`); **Operations journal** shows the operation, **Budgets** the
   reserve. When the agent has its report, it reports what it paid, and the operation is charged:

   ```bash
   curl -s http://localhost:8081/v1/spend/settle -H "Authorization: Bearer $KEY"      -H "Content-Type: application/json" -d '{"authorizationId":"<authorizationId>","amount":40}'
   ```
4. Ask for an amount above the approval threshold (`"amount":150`): `403 approval_required` with an approval id.
   **Approvals**: the request appears a moment later; approve it as the owner. Repeat the same request with the
   same `Idempotency-Key` and it passes. (The agent's `approvalId` is what it polls at the gateway,
   `GET /v1/spend/approvals/{id}`; people decide in **Approvals**.)
5. One more request of $150 with a new key: only $10 is left of the $200 limit, so `402 budget_exceeded` — before
   any money moved.
6. **Operations journal → the settled operation**: the receipt with its hash and signature. "Batches of these receipts are
   anchored in a NEAR contract; anyone can check a receipt without trusting us." Show it without Docker:
   `uv run python tools/near/demo.py verify` (a real testnet transaction; the tampered receipt fails).

## 4. People and access (2 minutes)

- Sign out, sign in as `ecarter` / `demo`: the employee sees only **My cabinet** — their licenses, a request for a
  license (served from the company's pool first), purchase requests, documents.
- **Org structure and access** (as the owner): roles owner, finance, IT admin, agent owner, auditor; offboarding
  an employee revokes their access and frees their licenses.

## 5. Close (2 minutes)

- What is real today and what is not (Stripe billing, AWS Terraform, SOC 2) —
  [BUYER_BRIEF.md](BUYER_BRIEF.md).
- Every number they saw came from synthetic data.

## If something goes wrong

[docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md). The most common: Docker with less than 8 GB of memory, and
the first Keycloak start taking a minute.
