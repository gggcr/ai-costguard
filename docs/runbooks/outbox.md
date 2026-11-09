# Outbox: события не доставляются

Алерты: `OutboxStuck` (старейшее недоставленное событие старше 15 минут), `OutboxRetryFailing`. Метрики
`costguard_outbox_pending_events`, `costguard_outbox_oldest_pending_seconds`, `costguard_outbox_retry_failures_total`.

Ядро пишет событие в `event_publication` в одной транзакции с изменением и отправляет после коммита
(`shared.outbox.KafkaOutbox`); `OutboxRetry` раз в минуту повторяет недоставленное. Пока событие не доставлено,
шлюз не знает нового агента, бюджета или мандата, нотариус — квитанции, ClickHouse — списания.

## Что смотреть
1. Логи ядра: `kubectl -n costguard logs deploy/costguard-core | grep -i "outbox\|Kafka did not take"`.
   - `Kafka did not take the message for <топик>` — Kafka недоступна или топика нет (`auto_create_topics_enable = false`).
   - ошибка Schema Registry (`409`) — схема несовместима с зарегистрированной: изменение `schemas/avro` не прошло
     совместимость (ADR 0018). Откатить релиз или зарегистрировать схему.
2. Что застряло:
   ```sql
   select listener_id, event_type, count(*), min(publication_date)
   from event_publication where completion_date is null group by 1, 2 order by 4;
   ```
3. Состояние Kafka — в консоли Yandex Cloud (Managed Kafka, хосты и диски).

## Исправить
- Причина устранена — `OutboxRetry` отправит всё сам за минуту; перезапуск ядра тоже отправляет недоставленное.
- Событие, которое не уйдёт никогда (битое), держит алерт. Решить с владельцем модуля: исправить данные и дождаться
  повтора, или удалить строку из `event_publication`, понимая, что потребитель его не получит (например, агент не
  появится в шлюзе — пересохранить агента).
- Потребители дедуплицируют по ключу: повторная отправка безопасна.
