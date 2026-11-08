# Load test of the agent gateway

`spend.js` (k6) sends thousands of operations a second through the gateway for one agent: each one reserves
(`/v1/spend/authorize`), then settles a part of the reserve or, one in ten, releases it. The agent has a hard
limit, and the test fails if more was ever charged than the limit:

- k6 counts what it settled (`settled_amount`, threshold `count<=LIMIT`) and fails on any answer other than 200 or
  402 (`unexpected`), and on a 99th percentile of authorize above 250 ms;
- `load.py check` reads the agent's budget in Redis afterwards: spent plus reserved must not be above the limit.

The limit is meant to be hit: 2000 operations a second for a minute at 10 roubles want 1.2 million, the default
limit is 500 000, so most of the run is refusals with 402, which is the point.

## Run
On GitHub: Actions → load → Run workflow (rate, duration, limit, amount). It starts Redis, Kafka and the Schema
Registry from `infra/local/docker-compose.yml`, the gateway from its jar, seeds the agent, runs k6 and checks Redis;
`summary.json` and `gateway.log` are kept as artifacts.

By hand, with the stack up (`make up`) and the gateway running:

```
uv run python tests/load/load.py seed --limit 500000        # prints LOAD_SECRET, LOAD_TENANT, LOAD_BUDGET
k6 run --summary-export summary.json -e SECRET=<LOAD_SECRET> -e LIMIT=500000 -e RATE=2000 -e DURATION=60s tests/load/spend.js
LOAD_TENANT=... LOAD_BUDGET=... uv run python tests/load/load.py check --summary summary.json
```

`seed` publishes the agent and its budgets to Kafka as the core would, so the core is not needed.
