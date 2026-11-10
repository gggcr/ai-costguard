# 0048. Prometheus, Grafana и трейсы в репозитории

Статус: принято, 2026-11-11.

## Контекст

На доске (фреймы 11 и 12) наблюдаемость — это Prometheus, Grafana, Loki и OpenTelemetry Collector, а их настройки
лежат в `infra/observability`. В репозитории были ServiceMonitor, PrometheusRule, дашборд и AlertmanagerConfig в
чарте и Loki с Alloy в Argo CD (ADR 0035). Сами Prometheus, Alertmanager и Grafana ставились kube-prometheus-stack
вне репозитория, и как их настроить, чтобы они увидели объекты чарта, нигде не было записано.

Трейсов не было совсем. Ядро и шлюз собраны с `spring-boot-starter-opentelemetry`, но адрес OTLP не задан, и
экспортёр не создаётся: trace id был только в ошибках.

## Решение

- `deploy/argocd/observability.yaml` ставит ещё три приложения в namespace `observability`:
  - `kube-prometheus-stack` 66.3.1. Prometheus берёт ServiceMonitor и PrometheusRule всех релизов
    (`*SelectorNilUsesHelmValues: false`). Alertmanager берёт AlertmanagerConfig с меткой
    `alertmanagerConfig: costguard`. Grafana подхватывает дашборды и источники данных из всех namespace. Пароль
    администратора берётся из секрета `grafana-admin`. Плоскость управления управляемого Kubernetes не скрейпится;
  - `tempo` 1.14.0 хранит трейсы неделю;
  - `opentelemetry-collector` 0.110.0. Его конфиг заменяет конфиг чарта целиком и совпадает с
    `infra/observability/otel-collector.yaml`, это проверяет тест.
- Collector перед отправкой в Tempo удаляет атрибуты, в которых могут быть персональные данные: `enduser.id`,
  `user.email`, заголовок авторизации, строку запроса (ADR 0023).
- В чарте появился `tracing.enabled`. Включённый задаёт ядру и шлюзу
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, выключенный — `MANAGEMENT_TRACING_EXPORT_ENABLED=false`.
  По умолчанию выключен, как и `logging.enabled`: оба включаются, когда стек наблюдаемости уже стоит.
- `infra/observability` — конфиг Collector и локальный стек: Prometheus, Grafana с тем же дашбордом и теми же uid
  источников, Tempo. Профиль `observability` в `infra/local/docker-compose.yml`, команда `make observability`.
  Локально ядро и шлюз отправляют трейсы, когда запущены с `TRACING=true`.
- Дашборд и правила алертов остаются в чарте: они выходят вместе с сервисами, а их пороги — это values.

## Последствия

- Перед первой синхронизацией в `observability` нужны секреты `loki-s3` и `grafana-admin`
  (docs/runbooks/observability.md).
- Python-сервисы трейсы пока не отправляют: в них нет инструментирования OpenTelemetry. Collector их примет, когда
  оно появится.
- Проекту Argo CD `observability` разрешены CRD и вебхуки. Они нужны оператору Prometheus.
