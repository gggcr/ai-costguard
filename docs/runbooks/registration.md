# Регистрация, приглашения и тарифы

Решения: [ADR 0032](../adr/0032-self-registration-and-invitations.md), [ADR 0033](../adr/0033-pricing-plans-and-limits.md),
[ADR 0034](../adr/0034-payment-provider-interface.md).

## Включить регистрацию и приглашения на стенде
Локально всё уже настроено: realm `infra/keycloak/costguard-realm.json` создаёт клиента и шлёт почту в Mailpit
(http://localhost:8025). Realm импортируется только в пустой Keycloak: если локальный Keycloak поднят раньше, пересоздайте
его контейнер (`docker compose ... rm -sf keycloak && make up`).

На staging и prod — в своём Keycloak (realm `costguard`):
1. **Клиент `costguard-registration`**: confidential, только Service accounts (без Standard flow и Direct access).
   В Service account roles → `realm-management`: `manage-users`, `view-users`, `query-users`, `view-realm`.
2. **Секрет клиента** — в Vault или Lockbox, в ядро как `KEYCLOAK_REGISTRATION_CLIENT_SECRET`. Пусто — регистрация и
   приглашения отвечают `503 registration_unavailable`. Ротация — как у остальных секретов ([secrets.md](secrets.md)).
3. **Почта Keycloak**: Realm settings → Email — SMTP-сервер и адрес отправителя. Без неё Keycloak не отправит письмо,
   а ядро ответит `503 setup_email_failed` (регистрация при этом создана: повтор того же запроса отправит письмо снова).
4. **Адрес возврата**: `WEB_URL` ядра должен входить в Valid redirect URIs клиента `costguard-frontend`.
5. **Адрес клиента за ingress**: по умолчанию ядро берёт его из `X-Forwarded-For` от внутренних адресов прокси
   (`FORWARD_HEADERS_STRATEGY=native`). Если лимит частоты срабатывает у всех сразу, ядро видит адрес ingress:
   проверьте, что ingress передаёт `X-Forwarded-For`.

## Регистрация не доходит до клиента
- `429 too_many_registrations` — лимит с адреса или на почту (`REGISTRATION_LIMIT_*`). Окно скользящее, ждать не
  больше `REGISTRATION_LIMIT_WINDOW`.
- Письма нет: Keycloak → Events (включите Save events) покажет `SEND_EXECUTE_ACTIONS` или ошибку SMTP. Повторный
  `POST /api/v1/registrations` с той же почтой отправит ссылку снова, пока регистрация не подтверждена.
- Ссылка истекла (`REGISTRATION_LINK_LIFESPAN`): тот же повтор регистрации.
- Неподтверждённая регистрация удаляется через `REGISTRATION_UNCONFIRMED_FOR` вместе с учётной записью; после этого
  почту можно регистрировать заново. Ожидающие удаления:
  ```sql
  select tenant_id, expires_at from registration_timer order by expires_at;
  ```

## Тарифы
- Лимиты и цены — `costguard.plans.catalog` в `application.yaml`, переопределяются переменными вида
  `COSTGUARD_PLANS_CATALOG_START_LIMITS_AGENTS=20`. Шлюз узнаёт лимит операций из `tenants.plans.v1` при следующем
  изменении тарифа компании.
- Назначить тариф компании, существовавшей до тарифов (у неё нет строки `tenant_plan` и нет лимитов): оплата через
  `POST /api/v1/plan/checkout` от владельца создаёт тариф.
- `402 plan_read_only` у всей компании — закончился пробный или оплаченный период; владелец оплачивает тариф,
  ограничение снимается сразу (шлюз — после доставки `tenants.plans.v1`).
- `402 plan_limit_exceeded` — тело ответа говорит, какой лимит, сколько разрешено и сколько есть.

## Оплата
Провайдер не выбран ([ADR 0034](../adr/0034-payment-provider-interface.md)). `PAYMENT_PROVIDER` пуст — checkout отвечает
`503 payment_provider_missing`. `PAYMENT_PROVIDER=dummy` — любой платёж проходит без денег: **только локальный стенд**.
