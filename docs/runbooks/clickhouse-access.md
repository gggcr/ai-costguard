# Row policies в ClickHouse

Row policies (ADR 0035) пускают читателя одной компании только к строкам этой компании в `cost_records` и
`agent_operations`, а значит и во всех витринах над ними. Читатель — пользователь `costguard_reader` с ролью
`costguard_tenant_reader`. В каждом запросе он передаёт компанию в настройке `SQL_tenant_id`. Без неё запрос
падает с ошибкой, а не возвращает строки всех компаний. Остальные пользователи (ядро, бэкапы, materialized views из
Kafka) видят всё, как раньше.

## Локально и в CI
`make clickhouse` применяет политики сам (`CLICKHOUSE_ACCESS_MANAGEMENT=1`, пароль читателя
`CLICKHOUSE_READER_PASSWORD` из `.env`). В CI у пользователя `default` есть право управлять доступом
(`schemas/clickhouse/tests/users.d/access-management.xml`), и тест проверяет, что читатель видит только свою компанию.

## В кластере (staging, prod)
В Managed ClickHouse роли и политики создаются только при **SQL-управлении пользователями**. Его нельзя выключить
обратно, а пользователей после включения ведут SQL-запросами, а не Terraform (`yandex_mdb_clickhouse_user`). Решение
принимает команда. Порядок:
1. Включить SQL-управление пользователями и задать пароль `admin` (консоль Yandex Cloud или `sql_user_management`
   и `admin_password` кластера в `deploy/terraform/yandex`). Пользователя `costguard` создать заново SQL-запросом с
   тем же паролем и правами на базу `costguard`, если провайдер его не оставил.
2. Положить `CLICKHOUSE_READER_PASSWORD` в `costguard-secrets` (см. [secrets.md](secrets.md)). Джобу миграций
   нужен пользователь, который управляет доступом, на время включения — `admin`.
3. `clickhouseMigrations.rowPolicies: true` в values окружения. Джоб применит `schemas/clickhouse/access` и
   запишет это в `schema_access`.
4. Задачам отчётов и прогнозов в Airflow дать `CLICKHOUSE_USER=costguard_reader`,
   `CLICKHOUSE_PASSWORD=<пароль читателя>` и `CLICKHOUSE_TENANT_SETTING=1`.

## Проверка
```sql
-- как costguard_reader, с настройкой SQL_tenant_id = '<компания>': только её строки
SELECT DISTINCT tenant_id FROM cost_records;
-- без настройки: ошибка, а не строки всех компаний
```

## Если отчёт или прогноз упал с Unknown setting SQL_tenant_id
Сервер не принимает настройки с префиксом `SQL_` (`custom_settings_prefixes` в конфигурации сервера). Пока префикс
не разрешён, уберите `CLICKHOUSE_TENANT_SETTING` у задачи, а политики не включайте.
