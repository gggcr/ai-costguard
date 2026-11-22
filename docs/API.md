# API

Two HTTP APIs: the **core API** for people and integrations, and the **gateway API** for agents. Both specs are
exported from the code, and a test fails when the code and the file disagree.

| API | Spec | Base URL (local) | Authentication |
|---|---|---|---|
| Core | [`schemas/openapi/core.yaml`](../schemas/openapi/core.yaml) (162 paths) | http://localhost:8080 | OIDC access token: `Authorization: Bearer <JWT>` |
| Gateway | [`schemas/openapi/gateway.yaml`](../schemas/openapi/gateway.yaml) | http://localhost:8081 | agent virtual key: `Authorization: Bearer cg_live_…` |

Core errors follow RFC 9457 (`application/problem+json`) with a stable machine-readable `code`, for example
`currency_in_use` or `language_unknown`. The gateway answers model calls in the OpenAI error shape, so standard clients parse it; all its codes are listed in
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Gateway: what an agent calls

**LLM calls through the provider's own API.** Point the provider's SDK at the gateway and use the agent's virtual
key instead of the provider key; the gateway meters tokens itself.

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8081/v1/providers/openai/v1", api_key="cg_live_...",
                default_headers={"X-CostGuard-Task": "ticket-42"})
client.chat.completions.create(model="gpt-4o-mini", messages=[{"role": "user", "content": "Hello"}])
```

Providers: `openai`, `anthropic`, `gigachat`, `yandex`. Paid data APIs configured by the operator are called as
`/v1/egress/<name>/<path>`.

**Anything else an agent pays for** (a purchase, a ticket, a paid lookup): authorize first, then settle what was
really spent, or release.

| Method and path | Purpose |
|---|---|
| `POST /v1/spend/authorize` | run the check ladder and reserve the amount; answers allowed, refused (with the reason), or held for a person (with an approval id) |
| `POST /v1/spend/settle` | charge the real amount of an authorization; the rest of the reserve is released |
| `POST /v1/spend/release` | cancel an authorization |
| `GET /v1/spend/approvals/{id}` | the decision on a held operation |

The SDKs wrap this: [`libs/python/costguard-sdk`](../libs/python/costguard-sdk) and
[`libs/js/costguard-sdk`](../libs/js/costguard-sdk).

```python
from costguard import CostGuard, ApprovalRequired
cg = CostGuard("cg_live_...", base_url="http://localhost:8081")
try:
    with cg.spend("Acme Data", 129, product="Company report", task_id="ticket-42",
                  task_goal="Due diligence of a new supplier") as op:
        report = buy_report()        # runs only if the gateway allowed the amount
        op.settle(report.price)      # charge what was really spent
except ApprovalRequired as e:
    cg.wait_for_approval(e.approval_id)
```

## Core: main resource groups

All paths start with `/api/v1`.

| Area | Paths |
|---|---|
| Company, people, access | `/company`, `/departments`, `/employees`, `/users`, `/invitations`, `/registrations`, `/scim` |
| Agents and rules | `/agents`, `/mandates`, `/policies`, `/budgets`, `/limit-requests` |
| Operations and approvals | `/operations`, `/approvals`, `/journal` |
| Software and spend | `/vendors`, `/contracts`, `/subscriptions`, `/licenses`, `/license-pool`, `/license-requests`, `/products`, `/catalog`, `/shadow-spend` |
| Audit | `/findings`, `/analytics` |
| Data in | `/imports`, `/connections`, `/microsoft` |
| Hardware | `/assets` |
| Employee cabinet | `/me/...`, `/purchase-requests`, `/vacations`, `/payroll`, `/documents` |
| Notifications | `/notifications` |
| Plans and billing | `/plans`, `/billing`, `/payments` |
| Privacy | `/erasures`, `/company/erasure` |

Live documentation of a running core: http://localhost:8080/swagger-ui.html.

## Events

Services exchange Kafka events whose Avro schemas are in [`schemas/avro`](../schemas/avro) (contracts first,
ADR 0003). Integrators normally use the REST APIs; the topics are internal.
