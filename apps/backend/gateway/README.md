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
| 4 | `RiskFilter` | Agent risk score | 423 `agent_suspended`, or 429 `rate_limited` |
| 5 | `BudgetFilter` | Reserve at every budget level | 402 `budget_exceeded` |
| 6 | `TaskFitFilter` | AI assessor, above the mandate threshold | Goes to approval |
| 7 | `ApprovalFilter` | Human approval | Refusal, reserve is returned |

The filters are stubs and are not registered as beans yet. A stub refuses the request instead of passing
it on, so an unfinished check can never allow spending.
