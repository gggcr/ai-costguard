# costguard-anomaly

The anomaly detector. A long-running Kafka consumer with two inputs and two outputs:

| In | Out | What |
|---|---|---|
| `cost.records.v1` | `findings.v1` | spend spikes per provider and per agent (`anomaly.spike`) and double charges (`anomaly.double-charge`), `detector.py` |
| `agent.operations.v1` | `agent.risk-signals.v1` (compacted) | risk 0–100 of every agent and task with an explanation; the gateway reads it at step 4 and stops an agent with `423` ([ADR 0016](../../../docs/adr/0016-anomaly-risk-score.md)) |

- `risk.py` — features: windows of 1 and 5 minutes, loops (SimHash of prompts, context growth, steps), spend speed
  against the agent's EWMA baseline, new vendor, splitting, night, errors.
- `models.py` — Isolation Forest and online models of `river`; saved to and restored from MLflow.
- `scorer.py` — rules and models into one score; thresholds 50/70/90, STOP only by a rule.

It keeps its history in memory: on start it reads both topics from the beginning and never commits offsets, so run
one replica per tenant set.

## Run

```sh
uv run costguard-anomaly                                       # from the repository root
docker build -f apps/python/anomaly/Dockerfile -t costguard-anomaly .
```

| Variable | Default | |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `SCHEMA_REGISTRY_URL` | `http://localhost:8085` | |
| `MLFLOW_TRACKING_URI` | empty | without it the models are learned anew on every start |
| `ANOMALY_TIME_ZONE` | `Europe/Moscow` | the day of a spike and the «night» of the risk rules |
| `ANOMALY_MODELS_SAVE_EVERY` | `5000` | operations between saves of the models to MLflow |


Traces (ADR 0050): with `COSTGUARD_TRACING=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` the service sends spans to the
OpenTelemetry Collector; the chart sets both with `tracing.enabled`.

## Tests

```sh
uv run pytest apps/python/anomaly
```
