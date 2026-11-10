# Agent gateway

Sits in the path of agent traffic and decides on each paid operation before any money is spent.
Only the gateway and Redis work synchronously during an operation; everything else gets events through Kafka.

Board frames: 2 (operation path), 3 (gateway check ladder), 13 (inside the services).

## Check ladder

| Order | Filter | Check | Refusal |
|---|---|---|---|
| 1 | `IdentityFilter` | Key is valid, agent is not suspended, the company's plan is not read-only and has operations of the month left ([ADR 0037](../../../docs/adr/0037-pricing-plans-and-limits.md)) | 401, 423 `agent_suspended`, or 402 `plan_read_only` / `plan_limit_exceeded` |
| 2 | `MandateFilter` | Mandate allows the model, vendor, amount and time | 403 `policy_denied` |
| 3 | `LoopFilter` | Repeat or loop within a task | Cached response, or 409 `duplicate_operation` |
| 4 | `RiskFilter` | Agent risk score | 429 `rate_limited`, approval, or 423 `agent_suspended` |
| 5 | `BudgetFilter` | Reserve at every budget level | 402 `budget_exceeded`|
| 6 | `TaskFitFilter` | AI assessor, above the mandate threshold: does the operation fit the task goal? | Goes to approval |
| 7 | `ApprovalFilter` | Human approval | Refusal, reserve is returned |

An operation sent to approval gets 403 `approval_required` with an `approvalId`. Once `approvals.decisions.v1`
says approved, the agent retries with the same `Idempotency-Key`, and the operation goes through every step again,
including the reserve, for at most the approved amount.

## AI assessor (step 6)

The agent states the goal of its task in `X-CostGuard-Task-Goal`, percent-encoded UTF-8 (the SDKs do it: `task_goal`
in Python, `taskGoal` in JS). Above the approval threshold of the mandate, `LlmTaskFitAssessor` asks a model of one
of the providers whether the operation clearly fits the goal: only a plain "ДА"/"yes" lets it pass; "no", "unsure",
anything else, an error, a refusal and a timeout (`ASSESSOR_TIMEOUT`, 3 s) send it to a person. Without a goal the
model is not asked. The assessor is on only when `ASSESSOR_PROVIDER` (a key of `costguard.gateway.providers`) and
`ASSESSOR_MODEL` are set; otherwise `HumanOnlyAssessor` sends every such operation to a person. The assessor's own
call uses the gateway's key and is a cost of CostGuard: it is not reserved in the company's budgets and not recorded
in `agent.operations.v1`. See ADR 0011.

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
- `/v1/egress/{name}/...`: a paid API (data, cloud), configured under `costguard.gateway.egress.<name>` (package
  `egress`). It goes through the same ladder as a model call: the price of the endpoint is reserved before the call,
  charged after a successful answer and given back on an error or a refusal. The price is the tenant's catalog
  `REQUEST` price of the vendor and product (`catalog.prices.v1`), else the `price` of the configuration; an endpoint
  with neither is refused with 403 `price_unknown`. `operations` give an endpoint, matched by method and Ant path,
  its own product and price. The gateway takes the agent's key off and puts the API's key (`header`, `value`) on.
  See ADR 0010.

A provider or a paid API that answers with an error, or does not answer, gets its reserve back and is recorded in
`agent.operations.v1` as `DENIED` with `upstream_error` and an actual cost of zero: the core charges nothing and
issues no receipt for it.

Every decision goes to `agent.operations.v1`. If Kafka or the Schema Registry is away, it goes to a file
(`costguard.gateway.buffer-file`) and is sent later.

## Not done

- Loop detection by content and SimHash belongs to the detector; the gateway only refuses repeated operation ids.
- Time and weekday for mandates use one time zone for all tenants, `COMPANY_TIME_ZONE`.
- Provider keys are read from properties; Vault fills them only when `VAULT_ENABLED=true`.

## Tests

Tests that extend `support/InMemoryGatewayTest` (paid APIs, the assessor) run the whole gateway without Docker, on an
in-memory reserve and a recording operation log. The others need Docker: Redis and Kafka run in Testcontainers, the
Schema Registry and the provider are stand-ins inside the test. On Windows the Kafka container is `KafkaNode`, because the Testcontainers
Kafka module advertises 0.0.0.0 under Docker Desktop.
