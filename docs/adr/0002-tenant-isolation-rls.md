# 0002. Изоляция компаний через RLS в PostgreSQL

## Контекст
Все компании живут в одной базе. Забытое `where tenant_id = ?` в одном запросе показало бы чужие счета.

## Решение
На каждой таблице с `tenant_id` включены `FORCE ROW LEVEL SECURITY` и политика по `app.tenant_id`.
`TenantSession.bind` ставит эту настройку на транзакцию. Приложение подключается обычной ролью: суперпользователь
и `BYPASSRLS` обходят RLS (`RlsRoleGuardTest`, `infra/local/postgres/fix-app-role.sql`). Тесты идут на настоящем
PostgreSQL от той же обычной роли.

## Последствия
- Фоновые задачи не видят компаний, пока не выберут одну. Им нужны таблицы без RLS, в которых только
  идентификаторы: `approval_timer`, `finding_wakeup`, `audit_tenant`.
- Сообщение Kafka без `tenant_id` ядро обработать не может. Поэтому в `journal.anchors.v1` добавлен
  необязательный `tenant_id`.
