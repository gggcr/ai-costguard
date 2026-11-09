# Место на дисках

Алерты `NodeDiskFilling` (узел Kubernetes) и `VolumeFilling` (том PVC): меньше 15% свободно
(`monitoring.diskFreeRatio`).

## Узлы и тома кластера
- Узел: `kubectl debug node/<узел> -it --image=busybox -- df -h /host`. Обычно это образы и логи контейнеров:
  `crictl rmi --prune` на узле или пересоздать узел (группа с автомасштабированием, `nodes_min`).
- Том: `kubectl -n costguard get pvc`; увеличить размер (`kubectl edit pvc`, класс `yc-network-ssd` расширяется).
- Задачи резервного копирования пишут дамп во временный `emptyDir` (`backups.scratchSize`, 20 ГБ): дамп вырос —
  увеличить.

## Диски управляемых баз (Prometheus их не видит)
PostgreSQL, Kafka и ClickHouse — Managed-сервисы Yandex Cloud; их диски видит Yandex Monitoring. Один раз на окружение
завести алерты в Monitoring → Алерты, канал уведомлений — те же Telegram и почта:
| Сервис | Метрика | Порог |
|---|---|---|
| Managed PostgreSQL | свободно / всего на диске кластера | < 15% |
| Managed Kafka | свободно / всего на диске брокера | < 15% |
| Managed ClickHouse | свободно / всего на диске хоста | < 15% |

Исправить: увеличить `postgres_disk_gb`, `kafka_disk_gb`, `clickhouse_disk_gb` в Terraform, это меняется без
пересоздания ([terraform.md](terraform.md)). У Kafka проверить `log_retention_hours`; у ClickHouse — нет ли потока
ошибок в `kafka_errors` (TTL 30 дней).
