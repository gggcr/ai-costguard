# Коннекторы падают

Алерт `ConnectorFailing`: больше половины выгрузок одного типа систем за 3 часа закончились ошибкой. Метрика
`costguard_connector_runs_total{type,status}` — без компании в метках.

## Найти
1. Какая компания: страница «Подключённые системы» каждой компании (`GET /api/v1/connections`) показывает статус и
   последнюю ошибку. Ошибка уже без секретов окружения и почты (ADR 0019, ADR 0023).
2. Логи агента: Airflow (DAG `connectors_hourly`) или `kubectl -n costguard logs job/costguard-connectors-…`.

## Частые причины
- `Missing setting or secret …` — у агента нет переменной окружения: [secrets.md](secrets.md).
- `401` / `403` от системы — токен клиента истёк или отозван: клиент выпускает новый, обновить секрет агента.
- `429` — лимит запросов системы; повторяется — реже выгружать.
- Таймауты — агент в сети клиента не видит систему: связаться с клиентом.

Падают все типы сразу — проблема у нас: Kafka (агент не может отчитаться) или ядро.

## FOCUS-выгрузки из бакета (ADR 0046)

1. В облаке включите выгрузку в формате FOCUS (CSV или Parquet):
   - AWS — Billing and Cost Management → Data Exports → FOCUS 1.0, CSV с GZIP или Parquet;
   - Azure — Cost Management → Exports → FOCUS cost and usage, CSV или Parquet;
   - Yandex Cloud — детализация в Object Storage.
2. Создайте ключ, который может только читать этот бакет.
3. В Keycloak заведите сервисный аккаунт компании с ролью `importer` (runbook `keycloak.md`).
4. В конфиг компании добавьте раздел:

   ```toml
   [focus_bucket]
   endpoint = "https://storage.yandexcloud.net"  # для AWS не нужен
   bucket = "billing-exports"
   prefix = "focus/"
   days = 7        # объекты старше не читаются
   accept = true   # FOCUS-файлы без вопросов подтверждаются сами
   ```

   В окружение воркера положите `<TENANT>_IMPORTER_TOKEN`, `<TENANT>_FOCUS_BUCKET_ACCESS_KEY` и
   `<TENANT>_FOCUS_BUCKET_SECRET_KEY`.
5. DAG `costguard_focus_bucket` срабатывает каждый час в :20. Итог запуска — JSON в логе задачи. В нём перечислено,
   что загружено, что подтверждено, что ждёт человека (с причиной), что пропущено и что упало.

Если надо прочитать бакет заново, удалите `$COSTGUARD_STATE_DIR/<tenant>/focus-bucket.json`. Файлы из окна `days`
загрузятся снова, но ядро узнает их по sha256, и дублей не будет.
