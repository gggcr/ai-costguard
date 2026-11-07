# Gateway: медленно или ошибки

Gateway стоит в пути каждой платной операции агента. Если он медленный, агенты ждут; если отвечает 5xx, они
останавливаются. Отказ 4xx (`budget_exceeded`, `policy_denied`) — это решение, а не сбой.

## Посмотреть
1. Дашборд «CostGuard», панели «Gateway p99» и «Gateway requests by status».
2. Какие URI медленные: `/v1/providers/*` — скорее всего, тормозит сам провайдер LLM; `/v1/spend/*` — Redis.
3. `kubectl -n costguard logs deploy/costguard-gateway --since=15m | grep -i error`.

## Частые причины
| Признак | Причина | Что делать |
|---|---|---|
| p99 растёт на `/v1/providers/openai/...` | Медленный провайдер | Ничего на нашей стороне; при долгом сбое предупредить клиентов |
| 5xx и `RedisConnectionFailure` в логах | Redis недоступен: gateway закрывается (fail closed) | Проверить кластер Redis в консоли Yandex Cloud, `kubectl get endpoints` |
| 5xx после выкладки | Ошибка релиза | Откатить, см. [deploy.md](deploy.md) |
| CPU у потолка, HPA на максимуме | Нагрузка | Поднять `services.gateway.autoscaling.maxReplicas` |

## Важно
- Не перезапускать Redis с очисткой: в нём счётчики бюджетов. Резервы, которые никто не закрыл, через 15 минут
  возвращает `ReservationSweeper`.
- Если Kafka недоступна, решения пишутся в буферный файл и уходят позже; это не причина 5xx.
