# Нотариус: квитанции не якорятся

Нотариус каждые 10 минут собирает квитанции в пакеты и записывает корни в контракт NEAR. Если лаг группы
`notary` не опускается до нуля два часа, срабатывает `NotaryNotAnchoring`.

## Посмотреть
- `kubectl -n costguard logs deploy/costguard-notary --since=1h`.
- Ошибка `NEAR query`/`NEAR send_tx` — сеть или RPC. Ошибка `NotEnoughBalance` — на аккаунте нотариуса нет NEAR
  на газ: пополнить аккаунт из `NEAR_ACCOUNT_ID`.
- `This batch is anchored with another root` — пакет с тем же id уже записан с другим корнем. Это значит, что
  квитанции пакета изменились, чего быть не может (журнал неизменяем триггером). Остановить нотариус и разбираться:
  это возможная подмена данных.

## Проверить пакет вручную
```
near view notary.costguard.near get_anchor '{"batch_id":"<uuid>"}'
near view notary.costguard.near verify '{"batch_id":"<uuid>","leaf":"<hash квитанции>","proof":["R:...","L:..."]}'
```
Путь квитанции в дереве отдаёт ядро: `GET /api/v1/operations/{operationId}/receipt`.

## Повторы безопасны
Повтор пакета с тем же корнем контракт принимает без изменений, ядро отмечает квитанции повторно без ошибки.
Перезапуск нотариуса после сбоя ничего не задвоит.

## Мандаты и подтверждения крупных операций (ADR 0009)
Запросы из `notary.requests.v1` нотариус читает группой `notary-requests`. Ответы уходят в
`notary.confirmations.v1`, ядро показывает `nearTxHash` у мандата и у согласования.

- `Request ... refused by the contract` в логе — контракт отклонил запрос, нотариус его пропустил. Текст
  `registered with another hash` или `confirmed by another approver` значит, что та же версия мандата или та же
  операция уже записана с другим хэшем. Так быть не может: остановить нотариус и разбираться, как с корнем пакета.
- `Request ... skipped, it is malformed` — запрос без хэша или версии; ищите ошибку в ядре.
- Лаг группы `notary-requests` растёт — RPC недоступен или кончился газ, см. выше.

```
near view notary.costguard.near get_mandate '{"mandate_id":"<uuid>","version":2}'
near view notary.costguard.near verify_mandate '{"mandate_id":"<uuid>","version":2,"mandate_hash":"<hex>"}'
near view notary.costguard.near is_confirmed '{"operation_hash":"<hex>","approver_hash":"<hex>"}'
```
Хэш мандата отдаёт `GET /api/v1/mandates/{id}` (`mandateHash`), хэш операции — `GET /api/v1/approvals/{id}`
(`notaryHash`). Из чего они считаются, описано в ADR 0009.

## Обновление контракта
Новая версия контракта меняет формат состояния. Деплой и перенос — одной транзакцией:
```
near deploy notary.costguard.near costguard_notary.wasm --initFunction migrate --initArgs '{}'
```
