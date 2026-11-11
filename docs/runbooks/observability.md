# Наблюдаемость: метрики, алерты, логи, трейсы

Решения: [ADR 0026](../adr/0026-alerting.md) (алерты), [ADR 0035](../adr/0035-ingress-keycloak-logs-row-policies.md)
(логи), [ADR 0048](../adr/0048-observability-stack.md) (Prometheus, Grafana, трейсы).

| Что | Где описано | Где работает |
|---|---|---|
| Prometheus, Alertmanager, Grafana | `deploy/argocd/observability.yaml`, приложение `kube-prometheus-stack` | namespace `observability` |
| Метрики сервисов и алерты | чарт: `monitoring.serviceMonitor`, `monitoring.rules`, `templates/monitoring.yaml` | ServiceMonitor и PrometheusRule рядом с сервисами |
| Дашборд «CostGuard» | `deploy/helm/costguard/dashboards/costguard.json` | ConfigMap с меткой `grafana_dashboard` |
| Доставка алертов | чарт: `alerting.*` ([alerting.md](alerting.md)) | AlertmanagerConfig |
| Логи | приложения `loki`, `alloy`; чарт: `logging.enabled` | Loki, источник «Loki CostGuard» |
| Трейсы | приложения `otel-collector`, `tempo`; конфиг `infra/observability/otel-collector.yaml`; чарт: `tracing.enabled` | Tempo, источник «Tempo» |

## Включить в кластере
1. Секреты в namespace `observability`:
   ```sh
   kubectl create namespace observability
   kubectl -n observability create secret generic loki-s3 --from-literal=S3_ACCESS_KEY=… --from-literal=S3_SECRET_KEY=…
   kubectl -n observability create secret generic grafana-admin --from-literal=admin-user=admin --from-literal=admin-password="$(openssl rand -base64 24)"
   ```
2. `kubectl apply -f deploy/argocd/observability.yaml`; дождаться Synced/Healthy у шести приложений.
3. В values окружения чарта: `logging.enabled: true`, `tracing.enabled: true`; при доставке алертов — `alerting.*`.
4. Проверить: Grafana (`kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80`) →
   дашборд «CostGuard» с данными; Explore → Loki: `{namespace="costguard"}`; Explore → Tempo: поиск по
   `service.name=core`.

## Локально
`make demo`, затем `TRACING=true make demo observability` (переменная нужна ядру и шлюзу при запуске):
Grafana http://localhost:3001 (admin / admin), Prometheus http://localhost:9090. Алертов локально нет: правила
живут в PrometheusRule чарта.

## Неполадки
| Признак | Что делать |
|---|---|
| Дашборд пустой | Prometheus → Status → Targets: есть ли `costguard-core`, `costguard-gateway`. Нет — у Prometheus не сняты селекторы (`*SelectorNilUsesHelmValues: false`) или `monitoring.serviceMonitor` выключен |
| Алерты не приходят | [alerting.md](alerting.md); метка `alertmanagerConfig: costguard` у AlertmanagerConfig должна совпадать с селектором Alertmanager |
| Трейсов нет | `tracing.enabled` в values; в поде ядра `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`; `kubectl -n observability logs deploy/otel-collector` — ошибки экспорта в Tempo |
| Трейсов мало | Доля задаётся `TRACE_SAMPLING` ядра (по умолчанию 0.1); Python-сервисы пишут только записанные трейсы (ADR 0050) |
| Нет спанов Python-сервиса | В его поде `COSTGUARD_TRACING=true` и `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`; в логе при старте строка «Traces of costguard-… go to …» |
| Логов нет | [ADR 0035](../adr/0035-ingress-keycloak-logs-row-policies.md): Alloy видит только поды с `app.kubernetes.io/part-of=costguard` в `costguard` и `costguard-staging` |
