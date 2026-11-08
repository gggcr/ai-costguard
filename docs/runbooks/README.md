# Runbooks

Что делать, когда сработал алерт или нужно выполнить операцию. Ссылки из алертов (`runbook` в
`deploy/helm/costguard/templates/monitoring.yaml`) ведут сюда.

| Runbook | Когда |
|---|---|
| [gateway.md](gateway.md) | `GatewaySlow`, `GatewayErrors`, агенты жалуются на отказы |
| [kafka-lag.md](kafka-lag.md) | `ConsumerLagGrowing`, масштабирование воркеров KEDA |
| [notary.md](notary.md) | `NotaryNotAnchoring`, ошибка NEAR |
| [core.md](core.md) | `CoreDown`, `DatabasePoolExhausted` |
| [secrets.md](secrets.md) | Создать или сменить секреты |
| [terraform.md](terraform.md) | Поднять или изменить окружение в Yandex Cloud |
| [deploy.md](deploy.md) | Выкатить релиз, откатить |
| [mlflow.md](mlflow.md) | Модели детектора в MLflow: включить, посмотреть, откатить |
