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
| 5 | `BudgetFilter` | Reserve at every budget level | 402 `budget_exceeded`|
| 6 | `TaskFitFilter` | AI assessor, above the mandate threshold | Goes to approval |
| 7 | `ApprovalFilter` | Human approval | Refusal, reserve is returned |

An operation sent to approval gets 403 `approval_required` with an `approvalId`. Once `approvals.decisions.v1`
says approved, the agent retries with the same `Idempotency-Key`, and the operation goes through every step again,
including the reserve, for at most the approved amount.

## What the gateway holds

| State | Where | From |
|---|---|---|
| Agents and key hashes, policy snapshots, prices, risk signals, approval decisions, the budget tree | Memory of every instance | Compacted topics, read from the start by each instance |
| Budget counters: limit, spent, reserved | Redis, shared | Limits from `budgets.limits.v1`; spent and reserved only from the gateway's Lua scripts |

Counters are in millionths of the currency, as integers. Every key of a tenant has the `{tenant}` hash tag.
`reserve.lua` takes every level or none; `settle.lua` and `release.lua` are safe to repeat; `limit.lua` ignores an
older version. A reserve that is never settled is released by `ReservationSweeper` after
`costguard.gateway.reservation-hold` (15 minutes).

## Calls

- `POST /v1/spend/authorize`, `/settle`, `/release`, `GET /v1/spend/approvals/{id}`: purchases and paid APIs that
  the agent pays itself. Spec: `schemas/openapi/gateway.yaml`.
- `/v1/providers/{openai|anthropic|gigachat|yandex}/...`: the provider's own API. The gateway counts input tokens
  (jtokkit; for providers other than OpenAI, cl100k plus 25%), sets `max_tokens` (1024 if absent; lower if the
  budget only fits less), reserves, swaps the virtual key for the provider key, and settles from the `usage` of
  the response, streamed or not. A provider error releases the reserve. A model with no price is refused with
  403 `price_unknown`.
- `/v1/egress/{name}/...`: a paid API priced per request from the catalog, configured under
  `costguard.gateway.egress`.

Every decision goes to `agent.operations.v1`. If Kafka or the Schema Registry is away, it goes to a file
(`costguard.gateway.buffer-file`) and is sent later.

## Not done

- The AI assessor of step 6: agents send no task goal yet, so every operation above the threshold goes to a human
  (`HumanOnlyAssessor`).
- Loop detection by content and SimHash belongs to the detector; the gateway only refuses repeated operation ids.
- Time and weekday for mandates use one time zone for all tenants, `COMPANY_TIME_ZONE`.
- Provider keys are read from properties; Vault fills them only when `VAULT_ENABLED=true`.

## Tests

`./gradlew :gateway:test` needs Docker: Redis and Kafka run in Testcontainers, the Schema Registry and the
provider are stand-ins inside the test. On Windows the Kafka container is `KafkaNode`, because the Testcontainers
Kafka module advertises 0.0.0.0 under Docker Desktop.
