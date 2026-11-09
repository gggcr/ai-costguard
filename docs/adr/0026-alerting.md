# 0026. Алерты: метрики ядра, сверка бюджетов, доставка в Telegram и на почту

## Контекст
В чарте было пять правил (шлюз, лаг Kafka, нотариус по лагу, ядро, пул соединений), все со ссылкой на runbook, но
без доставки. Для запуска нужны ещё ошибки outbox, расхождение сверки бюджетов, падения коннекторов, место на дисках
и доставка в Telegram и на почту. Своих метрик у ядра не было ни одной, а сверки бюджетов не было вовсе: README
обещал её, код выставлял нулевую поправку. Канал доставки (Alertmanager из чарта: critical — Telegram и почта,
warning — Telegram) выбран владельцем продукта 2026-11-09.

## Решение
- **Метрики ядра** (Micrometer → `/actuator/prometheus`):
  - `costguard_outbox_pending_events`, `costguard_outbox_oldest_pending_seconds` — недоставленные события Spring
    Modulith (таблица без RLS, запрос кэшируется на 30 секунд), `costguard_outbox_retry_failures_total`;
  - `costguard_journal_receipts_issued_total`, `costguard_journal_last_anchor_timestamp_seconds` — квитанции и
    последний якорь от нотариуса;
  - `costguard_connector_runs_total{type,status}` — выгрузки коннекторов, без компании в метках;
  - `costguard_budget_reconciliation_*` — сверка.
- **Сверка бюджетов** (`ledger.BudgetReconciliation`, раз в час). Сумма разрешённых операций с фактической стоимостью
  в ClickHouse (то, что списал шлюз) сравнивается с суммой списаний на бюджетах агентов в PostgreSQL (то, что
  записало ядро) — по компании и валюте, за сутки, закончившиеся час назад, чтобы опоздавшие сообщения успели дойти.
  Расхождение больше копейки — агент тратит без бюджета, проводка не удалась или outbox потерял операцию. В лог идут
  id компаний и суммы, в метрики — число расхождений и наибольшее. Без ClickHouse сверка не идёт и её метрик нет.
- **Правила** (`templates/monitoring.yaml`): `GatewayDown`, `GatewaySlow`, `GatewayErrors`, `ConsumerLagGrowing`,
  `OutboxStuck` (старейшее событие старше 15 минут), `OutboxRetryFailing`, `NotaryNotAnchoring`,
  `NotaryAnchorsMissing` (квитанции идут, якорей нет два часа), `ConnectorFailing` (больше половины выгрузок типа за
  3 часа упали), `BudgetReconciliationMismatch`, `BudgetReconciliationNotRunning`, `CoreDown`,
  `DatabasePoolExhausted`, `NodeDiskFilling` и `VolumeFilling` (меньше 15% свободно), `BackupFailed`, `BackupMissing`.
  У каждого `severity`, `summary`, `runbook` (путь) и `runbook_url` (ссылка в сообщении). Пороги — в values.
- **Доставка** (`templates/alerting.yaml`, `alerting.enabled`): `AlertmanagerConfig` для Alertmanager из
  kube-prometheus-stack. Warning — в Telegram, critical — в Telegram и на почту, повтор каждые 4 часа, «решено» тоже
  приходит. Токен бота и пароль SMTP — в секрете `costguard-alerting`, чарт ссылается на ключи, значений не знает.
  Включить без чата или почты нельзя: рендер падает.
- **Диски управляемых баз** Prometheus не видит. Их смотрят алерты Yandex Monitoring на свободное место кластеров
  PostgreSQL, Kafka и ClickHouse, заведённые руками по runbook `disks.md`: в коде их не описать, ресурса Terraform для
  алертов Monitoring мы не используем.
- `tools/tests/test_alerts.py` проверяет, что каждый runbook существует, что каждая метрика в правилах — та, что
  регистрирует ядро, и как маршрутизируется доставка.

## Последствия
- Правила на `kafka_consumergroup_lag`, `kube_*` и `node_*` требуют kafka-exporter, kube-state-metrics и
  node-exporter (последние два есть в kube-prometheus-stack). Без них правила молчат, а не врут.
- `costguard_journal_last_anchor_timestamp_seconds` после перезапуска равен нулю до первого якоря; `for: 30m` у
  `NotaryAnchorsMissing` перекрывает десятиминутный цикл нотариуса.
- Сверка сравнивает только агентский уровень бюджета и только списания: возвраты провайдера ClickHouse не видит, их
  сверит отдельная задача, когда появятся.
- ClickHouse теперь читает и ядро: клиент один, `shared.clickhouse.ClickHouse`, им же удаляются строки компании
  (ADR 0021). Настройка переехала из `costguard.privacy.clickhouse` в `costguard.clickhouse`.
