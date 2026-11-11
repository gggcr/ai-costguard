# Observability

Metrics, dashboards, alerts, logs and traces of CostGuard ([ADR 0048](../../docs/adr/0048-observability-stack.md),
runbook [observability.md](../../docs/runbooks/observability.md)).

| Piece | Lives in | Cluster | Local (`make observability`) |
|---|---|---|---|
| OpenTelemetry Collector | `otel-collector.yaml` — the one config of both | `deploy/argocd/observability.yaml`, app `otel-collector` (a copy under `alternateConfig`, checked by `tools/tests/test_observability.py`) | `otel-collector`, OTLP on :4318 |
| Tempo (traces) | `local/tempo.yaml` | app `tempo` | `tempo` |
| Prometheus | `local/prometheus.yml` | app `kube-prometheus-stack`; ServiceMonitors of the chart | `prometheus`, http://localhost:9090 |
| Grafana | `local/grafana/` | app `kube-prometheus-stack`; sidecars pick up dashboards and data sources | `grafana`, http://localhost:3001 |
| Dashboard «CostGuard» | `deploy/helm/costguard/dashboards/costguard.json` | ConfigMap of the chart | mounted from the chart |
| Alerts | `deploy/helm/costguard/templates/monitoring.yaml` (PrometheusRule), delivery `alerting.yaml` | Alertmanager of kube-prometheus-stack | — |
| Logs | Loki and Grafana Alloy | apps `loki`, `alloy` | — |

The dashboard and alerts stay in the Helm chart: they are released with the services they watch, and their
thresholds are values of the chart.

Traces: the core, the gateway and the Python services export OTLP when `tracing.enabled` is on in the chart, or
with `TRACING=true` locally (ADR 0050); the trace crosses Kafka in the `traceparent` header. The collector drops attributes that may carry personal data before anything is stored (ADR 0023).
