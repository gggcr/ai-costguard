# MLflow: модели детектора и прогнозов

MLflow хранит и версионирует модели (ADR 0016, ADR 0017): запуски и реестр — в базе `mlflow` PostgreSQL
(`MLFLOW_BACKEND_STORE_URI` в секрете), файлы моделей — в бакете `costguard-<env>-mlflow` Object Storage. Сервисы
ходят в `http://costguard-mlflow` (`MLFLOW_TRACKING_URI`) и S3-ключей не знают: MLflow сам отдаёт и принимает
артефакты (`--serve-artifacts`).

## Включить
1. `terraform apply` создаёт базу `mlflow` и бакет; `terraform output -json secrets` даёт
   `MLFLOW_BACKEND_STORE_URI` — добавить его в `costguard-secrets` (см. secrets.md).
2. Образ `mlflow` собирается из `deploy/mlflow/Dockerfile` с тем же тегом, что остальные.
3. `mlflow.enabled: true` в values окружения (в staging и prod уже включено), Argo CD → Sync.

## Посмотреть
```
kubectl -n costguard port-forward svc/costguard-mlflow 5000:80
```
http://localhost:5000 → Models → `costguard-anomaly-risk`: версии пакета моделей детектора, у каждой метрики
`agents`, `agents_trained`, `agents_with_forest`.

## Если MLflow недоступен
- Детектор не падает: при старте он пишет `Risk models not loaded`, оценивает агентов одними правилами и учит
  модели заново. Сохранить новую версию не выйдет, пока MLflow не вернётся (`Risk models not saved`).
- Под не стартует: `kubectl logs deploy/costguard-mlflow`. Ошибка подключения к базе — проверить
  `MLFLOW_BACKEND_STORE_URI`; `NoSuchBucket` или `AccessDenied` — бакет и ключи `S3_ACCESS_KEY`/`S3_SECRET_KEY`.

## Откатить модель
Новая версия появляется сама каждые 5000 операций. Чтобы детектор взял прежнюю, удалить в реестре версии новее
неё и перезапустить детектор: `kubectl -n costguard rollout restart deploy/costguard-anomaly`. Он загружает
последнюю оставшуюся версию.

## Локально
`docker compose -f infra/local/docker-compose.yml --profile ml up -d mlflow`, затем
`MLFLOW_TRACKING_URI=http://localhost:5000`. Артефакты — в SeaweedFS (бакет `costguard-mlflow` создаётся при
старте), запуски — в SQLite на томе `mlflow`.
