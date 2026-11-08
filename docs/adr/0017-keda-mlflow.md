# 0017. KEDA для Kafka-воркеров и MLflow в Helm-чарте

## Контекст
Фрейм 11 доски: в namespace analytics Python-воркеры масштабируются KEDA, рядом MLflow для моделей. Детектору
аномалий (ADR 0016) нужно хранить и версионировать модели, прогнозам — тоже. Воркеры Kafka масштабируются по
лагу, а не по CPU: ingest может стоять на сетевом вызове модели, а очередь при этом растёт.

## Решение
- KEDA: `keda.enabled` в values включает `ScaledObject` для каждого сервиса с секцией `keda` (группа, топики,
  `lagThreshold`, мин. и макс. реплик) — триггер `kafka` на каждый топик. Deployment такого сервиса не задаёт
  `replicas`, чтобы не спорить с KEDA. Аутентификация Kafka для скейлера — `TriggerAuthentication` из ключей
  секрета при `keda.kafkaAuth: true` (сейчас сервисы ходят в Kafka без SASL, поэтому выключено).
- Масштабируется только ingest. Детектор аномалий держит историю в памяти и читает топики с начала: две копии
  дважды публиковали бы находки и сигналы. Нотариус подписывает транзакции одним ключом NEAR: копии столкнулись бы
  на nonce. Обоим оставлена одна реплика с `Recreate`.
- MLflow: `mlflow.enabled` поднимает Deployment и Service `costguard-mlflow`. Запуски и реестр — в базе `mlflow`
  того же кластера PostgreSQL (`MLFLOW_BACKEND_STORE_URI` в секрете, Terraform его выдаёт); артефакты — в S3
  (бакет `costguard-<env>-mlflow` в Object Storage, локально SeaweedFS). MLflow сам раздаёт артефакты
  (`--serve-artifacts`), поэтому сервисам не нужны S3-ключи, только `MLFLOW_TRACKING_URI`, который чарт
  выставляет всем сервисам при включённом MLflow.
- Образ MLflow собирается из `deploy/mlflow/Dockerfile`: официальному не хватает драйвера PostgreSQL и boto3.
- В `values-staging.yaml` и `values-prod.yaml` оба включены. `tools/tests/test_helm.py` проверяет, что без них чарт
  прежний, а с ними — нужные объекты; workflow deploy-manifests его запускает.

## Последствия
- В кластере нужен оператор KEDA; без него Argo CD не применит `ScaledObject`.
- Реплик ingest не больше, чем партиций у его топиков.
- Одна реплика MLflow: недоступность MLflow не останавливает детектор (ADR 0016), но новые версии моделей
  не сохраняются, пока он не вернётся.
