# Доставка алертов

Решение: [ADR 0026](../adr/0026-alerting.md). Правила — `deploy/helm/costguard/templates/monitoring.yaml`, доставка —
`templates/alerting.yaml`. Critical приходит в Telegram и на почту, warning — в Telegram.

## Включить
1. Бот: @BotFather → `/newbot`, токен. Добавить бота в группу дежурных и узнать id чата
   (`https://api.telegram.org/bot<токен>/getUpdates` после сообщения в группе; у групп он отрицательный).
2. Почта: SMTP-сервер, ящик отправителя и его пароль.
3. Секрет:
   ```
   kubectl -n costguard create secret generic costguard-alerting \
     --from-literal=TELEGRAM_BOT_TOKEN=… --from-literal=SMTP_PASSWORD=…
   ```
4. Values окружения:
   ```yaml
   alerting:
     enabled: true
     telegram: { chatId: -100… }
     email: { to: oncall@…, from: alerts@…, smarthost: smtp.…:587, username: alerts@… }
   ```
5. Alertmanager должен выбирать этот конфиг: в kube-prometheus-stack
   `alertmanager.alertmanagerSpec.alertmanagerConfigSelector.matchLabels: { alertmanagerConfig: costguard }` и
   `alertmanagerConfigNamespaceSelector`, включающий namespace CostGuard.
6. Проверка: `kubectl -n monitoring port-forward svc/alertmanager-operated 9093`, затем
   `amtool --alertmanager.url=http://localhost:9093 alert add Test severity=critical namespace=costguard
   --annotation=summary=проверка`.

## Алерт не пришёл
- `kubectl -n monitoring logs alertmanager-…-0 | grep -i telegram`: 401 — неверный токен, 400 — бот не в чате.
- В UI Alertmanager (Status) нет маршрута `costguard` — не совпал селектор (шаг 5).
- Почта: `535` — пароль; `requireTLS` требует STARTTLS (порт 587).

## Каждое правило и его runbook
| Алерт | Runbook |
|---|---|
| `GatewayDown`, `GatewaySlow`, `GatewayErrors` | [gateway.md](gateway.md) |
| `ConsumerLagGrowing` | [kafka-lag.md](kafka-lag.md) |
| `OutboxStuck`, `OutboxRetryFailing` | [outbox.md](outbox.md) |
| `NotaryNotAnchoring`, `NotaryAnchorsMissing` | [notary.md](notary.md) |
| `ConnectorFailing` | [connectors.md](connectors.md) |
| `BudgetReconciliationMismatch`, `BudgetReconciliationNotRunning` | [budgets.md](budgets.md) |
| `CoreDown`, `DatabasePoolExhausted` | [core.md](core.md) |
| `NodeDiskFilling`, `VolumeFilling` | [disks.md](disks.md) |
| `BackupFailed`, `BackupMissing` | [backups.md](backups.md) |
