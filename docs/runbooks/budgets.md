# Сверка бюджетов: шлюз и журнал проводок расходятся

Решение: [ADR 0026](../adr/0026-alerting.md). Алерты: `BudgetReconciliationMismatch`, `BudgetReconciliationNotRunning`.

Раз в час ядро сравнивает за сутки, закончившиеся час назад, сумму разрешённых операций в ClickHouse (что списал
шлюз) и сумму списаний на бюджетах агентов в PostgreSQL (что записало ядро), по каждой компании и валюте.

## Найти расхождение
`kubectl -n costguard logs deploy/costguard-core | grep "Budget reconciliation"` — строка на каждую пару:
`tenant <id> RUB gateway 170.50 ledger 120.50 from … to …`.

## Причины, от частой к редкой
1. **Агент тратит без своего бюджета.** В логе ядра `Agent … has no budget; operation … is not in the ledger`. Шлюз
   пропустил операцию по бюджету отдела или компании, а проводить её ядру некуда. Завести бюджет агенту; прошлые
   операции сами не проведутся, они видны в ClickHouse.
2. **Ядро не дочитало `agent.operations.v1`.** Растёт лаг группы ядра (`ConsumerLagGrowing`):
   [kafka-lag.md](kafka-lag.md). Когда догонит, следующий раунд закроет алерт.
3. **Проводка падала.** Ошибки `OperationInbox` в логе; после исправления сообщение перечитается.
4. **Разные валюты** у операции и бюджета агента: сверка сравнивает по валюте, такая операция видна расхождением в
   обеих.

## `BudgetReconciliationNotRunning`
ClickHouse недоступен ядру (`Budget reconciliation failed` в логе): `CLICKHOUSE_URL`, пароль, сеть.
