# Персональные данные: удаление и сроки

Решения: [ADR 0021](../adr/0021-personal-data-retention-erasure.md), [ADR 0022](../adr/0022-activity-log.md),
[ADR 0024](../adr/0024-data-residency.md). Что где хранится: [реестр](../privacy/data-inventory.md).

## Запрос сотрудника на удаление
Запрос приходит к клиенту (он оператор данных), владелец компании выполняет его в CostGuard.
1. Сотрудник должен быть уволен: `PUT /api/v1/employees/{id}/dismissal`.
2. `POST /api/v1/employees/{id}/erasure` с токеном роли `owner`. Ответ — запись удаления: что удалено, что
   обезличено, что оставлено (`retained`, с основанием и датой) и что сделать руками (`manual`).
3. Шаги `manual`:
   - `connections.connection.settings` — настройка подключения указывает на почту человека: заменить на странице
     подключений;
   - `agents.agent.owner_id` — у агентов нет владельца: назначить;
   - `keycloak.user` — Admin API не настроен: удалить учётную запись в консоли Keycloak (realm `costguard`, найти по
     почте и проверить атрибут `tenant_id`).
4. Ответ человеку: что удалено и что хранится до конца учётного срока (акты, счета). Текст ответа — за юристом.

`409 employee_active` — сотрудник не уволен; `409 last_owner` — он единственный владелец, сначала назначить другого;
`503 identity_provider_unavailable` — Keycloak не ответил, ничего не изменилось, повторить.

## Удаление компании
1. Владелец: `POST /api/v1/company/erasure` с `{"confirmName": "<название как в настройках>"}`. Ответ 202 и дата
   удаления (через 30 дней).
2. До этой даты: `DELETE /api/v1/company/erasure` отменяет, `GET` показывает состояние.
3. Задача хранения (03:30 UTC) удаляет компанию в первый запуск после даты. Результат — в `company_erasure`:
   ```sql
   select completed_at, attempts, last_error, report from company_erasure where tenant_id = '<id>';
   ```
   `attempts > 0` и `last_error` — попытка упала (обычно Keycloak или ClickHouse недоступны), следующий запуск
   повторит. Ошибка подробно — в логе ядра: `Erasing company <id> failed`.
4. Руками, если в записи есть шаги `manual`: Keycloak (не настроен клиент) и ClickHouse (ядро без `CLICKHOUSE_URL`):
   ```sql
   ALTER TABLE cost_records DELETE WHERE tenant_id = toUUID('<id>');
   ALTER TABLE agent_operations DELETE WHERE tenant_id = toUUID('<id>');
   ```
   Если пайплайн хранит файлы в S3, а не в общем каталоге с ядром, удалить префикс компании в бакете:
   `yc storage s3 rm --recursive s3://costguard-<env>-imports/<id>/`.

## Клиент Keycloak для ядра (staging и prod)
Локальный стек импортирует клиента `costguard-privacy` из `infra/keycloak/costguard-realm.json` с секретом
`costguard-privacy-local`. В staging и prod:
1. Realm `costguard` → Clients → Create: `costguard-privacy`, Client authentication **on**, Service accounts
   **on**, остальные потоки выключить.
2. Service account roles → `realm-management`: `view-users`, `query-users`, `manage-users`. Больше ничего.
3. Секрет клиента → `KEYCLOAK_PRIVACY_CLIENT_SECRET` в `costguard-secrets` (см. [secrets.md](secrets.md)).
4. `external.keycloakAdminUrl` в values — адрес Keycloak без `/realms/...`.

Keycloak должен работать в том же регионе, что и остальные хранилища (ADR 0024).

## Сроки хранения
Значения по умолчанию в `application.yaml` (`costguard.privacy.retention`), меняются переменными `PRIVACY_RETAIN_*`
в `services.core.env` values окружения. `PRIVACY_RETENTION_ENABLED=false` останавливает задачу хранения (удаление
компаний продолжается). Что удалила задача, видно в журнале действий: `GET /api/v1/activity?action=privacy.retention_applied`.
