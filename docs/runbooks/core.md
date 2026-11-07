# Ядро недоступно или ждёт базу

## CoreDown
1. `kubectl -n costguard get pods -l app.kubernetes.io/name=core` и `describe` упавшего пода.
2. `CrashLoopBackOff` сразу после выкладки: в логах обычно ошибка миграции Flyway или старт без переменной.
   Откатить, см. [deploy.md](deploy.md).
3. `Database role '...' bypasses row-level security` — приложение подключилось
   суперпользователем. Выдать обычную роль: `infra/local/postgres/fix-app-role.sql`, секрет `DB_USER`.

## DatabasePoolExhausted
Запросы ждут соединения с PostgreSQL.
- Долгие запросы: `select pid, now() - query_start, query from pg_stat_activity where state <> 'idle' order by 2 desc`.
- Блокировки: `select * from pg_locks where not granted`.
- Если нагрузка выросла законно — добавить реплик ядра или поднять `spring.datasource.hikari.maximum-pool-size`,
  помня про лимит соединений роли (`conn_limit` в Terraform).

## Аудит и таймеры
Аудит, таймеры согласований и находок идут на каждом экземпляре. Они идемпотентны, лишний экземпляр ничего не
задвоит, но и не ускорит.
