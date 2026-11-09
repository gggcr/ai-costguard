# Резервные копии и восстановление

Решение: [ADR 0025](../adr/0025-backups.md). Алерты: `BackupFailed`, `BackupMissing`.

| Что | Когда (UTC) | Куда | Срок |
|---|---|---|---|
| PostgreSQL, `pg_dump -Fc` под `costguard_backup` | 01:00 | `s3://costguard-<env>-backups/postgres/<день>/costguard.dump.age` | 30 дней |
| ClickHouse, `cost_records` и `agent_operations` | 01:30 | `…/clickhouse/<день>/<таблица>.native.age` | 30 дней |
| Загруженные файлы, зеркало | 02:00 | `…/files/objects/<ключ>.age` | текущие + прежние версии 30 дней |
| Managed PostgreSQL, встроенные копии | окно Yandex | Yandex Cloud | 14 дней, восстановление на момент |

Все копии зашифрованы публичным ключом age. Закрытый ключ — у владельца, не в кластере.

## Включить (один раз на окружение)
1. Ключ age на машине владельца: `age-keygen -o costguard-<env>-backup.key`. Закрытый ключ сохранить в двух местах
   (сейф и менеджер паролей); без него копии не прочитать. Публичный (`age1…`) — в `backups.ageRecipient` values
   окружения.
2. `terraform apply` создаёт бакет `costguard-<env>-backups`, ключ KMS и пользователя `costguard_backup`. В секрет
   `costguard-secrets` добавить `BACKUP_DB_USER` и `BACKUP_DB_PASSWORD` из `terraform output -json secrets`.
3. Выдать роли права (если она появилась после миграции V21), под ролью приложения:
   `psql "<DB_URL без jdbc:>" -U costguard -f tools/backup/grant-backup-role.sql`.
4. Образ `backup` собрать и опубликовать вместе с остальными (`deploy/backup/Dockerfile`).
5. `backups.enabled: true` в values окружения, Argo CD синхронизирует.
6. Проверить сразу, не дожидаясь ночи:
   `kubectl -n costguard create job --from=cronjob/costguard-backup-postgres backup-check`.

## `BackupFailed` / `BackupMissing`
1. `kubectl -n costguard logs job/<имя job>` — сообщение скрипта.
   - `permission denied for table …` — у `costguard_backup` нет прав на новую таблицу: шаг 3 выше.
   - `AGE_RECIPIENT is not set` — нет ключа в values.
   - ошибка подключения к `…:6432` — база недоступна или пароль сменён (ротация, [secrets.md](secrets.md)).
   - `NoSuchBucket` / `AccessDenied` — бакет, ключи S3, или сервисному аккаунту не выдана роль на ключ KMS.
2. Исправить и запустить вручную: `kubectl -n costguard create job --from=cronjob/costguard-backup-<вид> backup-retry`.
3. Одна пропущенная ночь не страшна, пока работают встроенные копии Managed PostgreSQL; две подряд — поднять
   приоритет.

## Восстановить PostgreSQL
Сначала решить, нужен ли дамп: «откатить базу на час назад» быстрее восстановлением Managed PostgreSQL на момент
времени в новый кластер (консоль Yandex Cloud или `yc managed-postgresql cluster restore`). Дамп — когда кластера нет
или нужна копия старше, чем хранит Yandex.
1. Остановить запись: `kubectl -n costguard scale deploy costguard-core costguard-gateway --replicas=0`.
2. Пустая база, владелец — роль приложения (новый кластер или `create database costguard_restored owner costguard`).
3. На машине с ключом:
   ```
   export BACKUP_TARGET=s3://costguard-<env>-backups S3_ENDPOINT=https://storage.yandexcloud.net S3_ACCESS_KEY=… S3_SECRET_KEY=…
   python tools/backup/backup.py status
   python tools/backup/backup.py restore-postgres --day 2026-11-09 --identity costguard-<env>-backup.key \
     --target "postgresql://costguard:<пароль>@<хост>:6432/costguard_restored?sslmode=require"
   ```
4. Проверить: `select count(*) from flyway_schema_history` совпадает с числом миграций; проверка цепочки квитанций
   компании (`GET /api/v1/journal/verification`) — `valid: true`.
5. Переключить `external.postgresUrl` на восстановленную базу, вернуть реплики. Операции, принятые шлюзом после
   времени копии, лежат в Kafka 7 дней и в ClickHouse: сверка бюджетов покажет расхождение ([budgets.md](budgets.md)),
   его закрывает перечитывание `agent.operations.v1` группой ядра с нужного смещения.

## Восстановить ClickHouse
1. Создать пустые таблицы: `python schemas/clickhouse/apply.py` на целевом кластере.
2. `python tools/backup/backup.py restore-clickhouse --day <день> --identity <ключ> --database <база>`.
3. Что пришло после копии, ClickHouse дочитает из Kafka сам, если группа `clickhouse-*` не сдвинута.

## Восстановить файлы
`python tools/backup/backup.py restore-files --identity <ключ> --to s3://costguard-<env>-imports [--prefix <tenant>/]`.
Файл, удалённый меньше 30 дней назад, есть ещё и как прежняя версия в самом бакете загрузок:
`yc storage s3api list-object-versions --bucket costguard-<env>-imports --prefix <ключ>`.

## Учения
Раз в квартал восстановить вчерашнюю копию в отдельную базу по шагам выше и записать время. CI делает то же на каждом
push (`backup-restore`), но без настоящего бакета и ключа владельца.
