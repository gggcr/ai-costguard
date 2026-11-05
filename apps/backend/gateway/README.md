# Agent gateway

Sits in the path of agent traffic and decides on each paid operation before any money is spent.
Only the gateway and Redis work synchronously during an operation; everything else gets events through Kafka.

Board frames: 2 (operation path), 3 (gateway check ladder), 13 (inside the services).

## Check ladder

| Order | Filter | Check | Refusal |
|---|---|---|---|
| 1 | `IdentityFilter` | Key is valid, agent is not suspended | 401, or 423 `agent_suspended` |
| 2 | `MandateFilter` | Mandate allows the model, vendor, amount and time | 403 `policy_denied` |
| 3 | `LoopFilter` | Repeat or loop within a task | Cached response, or 409 `duplicate_operation` |
| 4 | `RiskFilter` | Agent risk score | 429 `rate_limited`, approval, or 423 `agent_suspended` |
| 5 | `BudgetFilter` | Reserve at every budget level | 402 `budget_exceeded`, or approval |
| 6 | `TaskFitFilter` | AI assessor, above the mandate threshold | Goes to approval |
| 7 | `ApprovalFilter` | Human approval | Refusal, reserve is returned |

An operation sent to approval gets 403 `approval_required` with an `approval_id`.

The steps are stubs. `CheckLadder` runs them in order, and both `CheckLadderFilter` (proxied provider calls)
and `SpendController` (`/v1/spend/*`) call it. A stub fails instead of passing, and a failed check becomes
503 `check_unavailable`, so an unfinished check can never allow spending.
