# Личность по Keycloak `sub`, согласующий в NEAR, сопоставление выплат

Что сделать на стенде после выкладки ADR 0069 и 0070 (миграции V47–V49) и как проверить это вручную. Все проверки —
на локальном стенде или staging с вымышленными учётными записями; prod — только по согласованию.

## 1. Почта в Keycloak: только администратор

Realm импортируется только при первом запуске Keycloak (`--import-realm`): правка `infra/keycloak/costguard-realm.json`
и `deploy/helm/costguard/files/keycloak-realm.json` в уже работающий realm **не попадает**. Меняется вручную.

Консоль администратора: realm `costguard` → Realm settings → User profile → атрибут `email` → Permissions →
«Who can edit»: оставить только `admin` (снять `user`) → Save. `view` для `user` не трогать.

Admin REST API (тот же результат, для скрипта): `GET /admin/realms/costguard/users/profile`, в атрибуте `email`
поставить `"permissions": {"view": ["admin", "user"], "edit": ["admin"]}`, отправить весь документ обратно
`PUT /admin/realms/costguard/users/profile`. Проверка: в Account Console (`/realms/costguard/account` → Personal info)
обычный пользователь видит почту, но не может её изменить.

## 2. Проверка на настоящем Keycloak (не выполнялась при аудите)

Аудит проверял ядро с поддельными токенами; поведение Keycloak 26.0.7 подтверждено только чтением исходников.
Порядок ручной проверки на локальном стенде (`make demo` или шаги из Makefile), с вымышленными адресами:

1. Пригласить сотрудника A (owner, `POST /api/v1/invitations` с `employeeId`), завести сотрудника B без входа.
2. До шага 1 раздела 1: войти как A в Account Console и сменить почту на адрес B. Ожидание: почта меняется,
   `email_verified` становится `false` (Admin API: `GET /admin/realms/costguard/users/{id}`).
3. Получить новый токен A (выйти и войти). В `/api/v1/me` — карточка A, не B; `/api/v1/me/payroll` — выплаты A.
4. Принять согласование от A (`PUT /api/v1/approvals/{id}/decision`): `decided_by` — сохранённая почта A,
   `decided_by_account` — учётная запись A, в событии нотариуса — хэш v2 от неё.
5. Сделать шаг 1 раздела 1 и повторить шаг 2: изменить почту больше нельзя.
6. Удалить пользователя B в Keycloak и создать заново с той же почтой: новая учётная запись Keycloak получает новый
   `sub` и не наследует учётную запись CostGuard прежнего, пока не войдёт с подтверждённой почтой и учётная запись не
   свободна.
7. Вход через Microsoft (ADR 0058, если подключён): первый вход связывает существующую учётную запись только после
   подтверждения письмом или паролем; `sub` токена — id пользователя Keycloak, не каталога Microsoft.

Результат каждого шага записать в задачу; расхождение с ожиданием — блокер выкладки.

## 3. Согласующий в NEAR: v1 и v2

`approval_request.approver_hash_version`: 1 — хэш посчитан до V49 из `decided_by`, 2 — из `decided_by_account`.
Пересчитать для проверки (ADR 0009, 0069): sha256 в нижнем hex от строк через `\n` —
v1: `costguard:approver:v1`, tenant_id, approval_id, `decided_by`; v2: `costguard:approver:v2`, tenant_id,
approval_id, `decided_by_account`. Сравнить с `approver_hash` (у v2) и с `is_confirmed` контракта. Хэши v1, уже
записанные в NEAR, не пересчитываются и не меняются.

## 4. Дубли адресов сотрудников перед уникальным индексом

Индекс `unique (tenant_id, lower(email)) where erased_at is null` (ADR 0070) ещё не добавлен. Перед его миграцией:

1. Выполнить только на чтение `tools/preflight/employee-email-duplicates.sql` от роли `costguard_backup`.
2. Пусто — индекс можно добавлять. Иначе для каждой строки владелец компании решает, какая карточка верна:
   неверной исправить адрес (`PUT /api/v1/employees/{id}`) или, если это ошибочная запись уволенного, удалить её
   данные по ADR 0021. Не удалять строки SQL-запросом.
3. Повторять п. 1, пока не станет пусто; только потом выкатывать миграцию с индексом.

Пока индекса нет, неоднозначный адрес безопасен (выплата никому, строка в `payroll_unmatched`), но каждый дубль
останавливает выплаты по нему.

## 5. Выплаты без сотрудника

`GET /api/v1/payroll/unmatched` (owner, finance): строка, причина (`ambiguous employee`, `employee not found`,
`bad email`), число кандидатов. Исправить данные (адрес сотрудника, адрес в источнике) и загрузить ту же выплату
снова: строка уйдёт сама. Срок хранения строк пока не установлен (ADR 0070, открытый вопрос).

## 6. Требования к паролю в Keycloak (аудит 2026-11-13)

В `deploy/helm/costguard/files/keycloak-realm.json` добавлена политика паролей: не короче 12 и не длиннее 128
символов, не совпадает с логином и почтой, не повторяет три последних. Как и раздел 1, в работающий realm это само не
попадает: Realm settings → Authentication → Policies → Password policy, добавить те же правила → Save, или Admin
REST API `PUT /admin/realms/costguard` с `"passwordPolicy": "length(12) and maxLength(128) and notUsername and
notEmail and passwordHistory(3)"`. Действует при следующей смене пароля; пароль, который ядро выдаёт новому
сотруднику (ADR 0059), — 14 символов, проходит. Локальный realm (`infra/keycloak`) не меняется: демо-вход
`demo` / `demo`.

## 7. Метрики и проверки здоровья на отдельном порту (аудит 2026-11-13)

Ядро и шлюз в Helm отдают `/actuator` (health, prometheus) на порту `managementPort` (9090, переменная
`MANAGEMENT_SERVER_PORT`). Service публикует его как `management` для проб и ServiceMonitor, ingress — только порт
`http`, поэтому `https://<api>/actuator/prometheus` снаружи отвечает 404. Проверка после выкладки:
`curl -s -o /dev/null -w '%{http_code}' https://<api-host>/actuator/prometheus` — 404; в Prometheus цели
`costguard-core` и `costguard-gateway` — up. Локальный стенд (`make demo`) не меняется: там актуатор на основном порту.
