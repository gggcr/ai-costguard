# Runbooks

Что делать, когда сработал алерт или нужно выполнить операцию. Ссылки из алертов (`runbook` в
`deploy/helm/costguard/templates/monitoring.yaml`) ведут сюда.

| Runbook | Когда |
|---|---|
| [gateway.md](gateway.md) | `GatewaySlow`, `GatewayErrors`, агенты жалуются на отказы |
| [redis.md](redis.md) | Redis шлюза недоступен, потерял данные или переполнен; что в нём лежит |
| [provider.md](provider.md) | Провайдер ИИ отвечает ошибками или медленно, сменить ключ провайдера |
| [kafka-lag.md](kafka-lag.md) | `ConsumerLagGrowing`, масштабирование воркеров KEDA |
| [notary.md](notary.md) | `NotaryNotAnchoring`, ошибка NEAR |
| [core.md](core.md) | `CoreDown`, `DatabasePoolExhausted` |
| [scim.md](scim.md) | Подключить Okta, Entra ID и другие провайдеры по SCIM, токены, что во что превращается |
| [secrets.md](secrets.md) | Создать или сменить секреты |
| [clickhouse-access.md](clickhouse-access.md) | Включить row policies в ClickHouse, читатель одной компании |
| [keycloak.md](keycloak.md) | Включить Ingress и Keycloak в кластере, секрет Keycloak, сервисные аккаунты компаний |
| [microsoft-login.md](microsoft-login.md) | Подключить вход через Microsoft 365 компании, первый вход, потерян телефон с кодами |
| [terraform.md](terraform.md) | Поднять или изменить окружение в Yandex Cloud |
| [deploy.md](deploy.md) | Выкатить релиз, откатить |
| [mlflow.md](mlflow.md) | Модели детектора в MLflow: включить, посмотреть, откатить |
| [backups.md](backups.md) | `BackupFailed`, `BackupMissing`; включить копии; восстановить PostgreSQL, ClickHouse, файлы |
| [observability.md](observability.md) | Prometheus, Grafana, логи и трейсы: включить, проверить, если пусто |
| [alerting.md](alerting.md) | Доставка алертов в Telegram и на почту; какой runbook у какого алерта |
| [outbox.md](outbox.md) | `OutboxStuck`, `OutboxRetryFailing` |
| [budgets.md](budgets.md) | `BudgetReconciliationMismatch`, `BudgetReconciliationNotRunning` |
| [connectors.md](connectors.md) | `ConnectorFailing` |
| [disks.md](disks.md) | `NodeDiskFilling`, `VolumeFilling`, диски управляемых баз |
| [smoke.md](smoke.md) | Smoke-тест staging после выкладки: настроить, разобрать падение |
| [key-leak.md](key-leak.md) | Утёк ключ или пароль |
| [privacy.md](privacy.md) | Запрос на удаление данных сотрудника, удаление компании, клиент Keycloak, сроки хранения |
| [registration.md](registration.md) | Включить регистрацию и приглашения на стенде: клиент Keycloak, почта Keycloak; оплата; отказы 402 по тарифу |
