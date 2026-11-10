# 0036. Самостоятельная регистрация компаний и приглашения сотрудников

## Контекст
До сих пор компания появлялась только из `tools/seed` (демо) или руками администратора: строка в `tenant` и
учётная запись в Keycloak с атрибутом `tenant_id`. Клиенты должны регистрироваться сами, а владелец — приглашать
сотрудников с ролями. Публичная регистрация открывает новую поверхность атаки: массовые регистрации, рассылка писем
на чужие адреса, перебор занятых e-mail.

## Решение
- **Регистрация.** `POST /api/v1/registrations` без токена: название компании, страна (ISO 3166-1 alpha-2), валюта
  (ISO 4217), e-mail, имя и фамилия владельца (Keycloak требует оба поля, иначе остановит владельца на первом входе).
  В одной транзакции создаются `tenant` (статус `pending`), `user_account` владельца, `registration` и пробный тариф
  (ADR 0037). До этого ядро создаёт учётную запись Keycloak: атрибут `tenant_id`, роль `owner`, обязательные
  действия `VERIFY_EMAIL` и `UPDATE_PASSWORD`. Транзакция не прошла — учётная запись удаляется.
- **Письмо шлёт Keycloak** (`execute-actions-email`): подтверждение адреса и установка пароля одной ссылкой, срок —
  `REGISTRATION_LINK_LIFESPAN`. Ядро не хранит и не проверяет одноразовые токены. Почта Keycloak настраивается в
  realm (`smtpServer`): локально — Mailpit, на стендах — свой SMTP (runbook).
- **Активация — по первому входу.** Keycloak не выдаёт токен, пока адрес не подтверждён и пароль не задан. Первый
  запрос с токеном `email_verified = true` переводит компанию в `active` (`FirstLogin`) и пишет `company.activated` в
  журнал действий. До подтверждения в журнал действий ничего не пишется: он только дополняется, а брошенная
  регистрация не должна оставлять следов.
- **Неподтверждённые регистрации удаляются.** `registration_timer` (только id и срок, без RLS) через
  `REGISTRATION_UNCONFIRMED_FOR` отдаёт компанию уборщику: если адрес всё же подтверждён — компания активируется,
  иначе удаляются учётная запись Keycloak, строки компании и тариф (tombstone в `tenants.plans.v1`). E-mail
  освобождается для новой регистрации.
- **Защита от злоупотреблений.**
  - Ограничение частоты в PostgreSQL (`registration_attempt`): не больше `REGISTRATION_LIMIT_PER_IP` попыток с
    адреса и `REGISTRATION_LIMIT_PER_EMAIL` на e-mail за `REGISTRATION_LIMIT_WINDOW`, общее для всех реплик ядра,
    без новой инфраструктуры. Считается каждая попытка, отказанные тоже. Хранятся только SHA-256 адреса и e-mail и
    только на время окна. Адрес клиента за ingress берётся из `X-Forwarded-For` (`server.forward-headers-strategy`,
    Tomcat доверяет только внутренним адресам прокси).
  - Подтверждение e-mail обязательно до любого входа и до активации.
  - Ответ всегда `202 check_email`: и для нового адреса, и для ожидающей регистрации (ссылка уходит повторно), и для
    занятого адреса (ничего не отправляется). Ответ не говорит постороннему, кто зарегистрирован.
- **Приглашения.** Владелец (`owner`) вызывает `POST /api/v1/invitations` с e-mail, именем и ролью: ядро создаёт
  учётную запись Keycloak компании с этой ролью, `user_account` и `invitation`, Keycloak шлёт то же письмо. Первый
  вход принимает приглашение. Повторная отправка — `POST /api/v1/invitations/{id}/resend`, отзыв до принятия —
  `DELETE /api/v1/invitations/{id}` (учётная запись и пользователь удаляются). `DELETE /api/v1/users/{id}` теперь
  удаляет и учётную запись Keycloak, созданную приглашением.
- **Клиент Keycloak.** Конфиденциальный клиент `costguard-registration` (секрет `KEYCLOAK_REGISTRATION_CLIENT_SECRET`)
  с сервисной учётной записью: `manage-users`, `view-users`, `query-users`, `view-realm` (чтение ролей realm). Отдельно
  от `costguard-privacy` (ADR 0021): у каждого свой секрет и своя ротация. Пустой секрет — регистрация и приглашения
  выключены (`503 registration_unavailable`).
- Keycloak за интерфейсом `IdentityProvider`: в тестах — реализация в памяти, HTTP-клиент проверяется на заглушке
  admin API.

## Последствия
- Новые переменные: `KEYCLOAK_REGISTRATION_CLIENT_SECRET`, `REGISTRATION_LINK_LIFESPAN`,
  `REGISTRATION_UNCONFIRMED_FOR`, `REGISTRATION_LIMIT_PER_IP`, `REGISTRATION_LIMIT_PER_EMAIL`,
  `REGISTRATION_LIMIT_WINDOW`, `FORWARD_HEADERS_STRATEGY`.
- Новые таблицы: `registration`, `invitation` (RLS), `registration_timer`, `registration_attempt` (без RLS: id и
  сроки или хэши без компании). В `tenant` — `country` и `status`; существующие компании `active`.
- В realm добавлены клиент `costguard-registration` и SMTP Mailpit для локального стенда; стенды настраивают SMTP и
  клиента сами (`docs/runbooks/registration.md`).
- Капчи нет: при массовых регистрациях с разных адресов её можно добавить перед `POST /registrations`, не меняя
  остального. Пользователи, добавленные через `POST /api/v1/users` без приглашения, учётной записи Keycloak от ядра
  не получают, как и раньше.
