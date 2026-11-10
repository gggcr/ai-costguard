# Keycloak и Ingress в кластере

Чарт может сам поднять Keycloak и Ingress (ADR 0035). По умолчанию оба выключены: включайте, когда готовы секрет,
DNS и сертификаты.

## Что нужно в кластере
- Ingress-контроллер ingress-nginx (`ingress.className`, по умолчанию `nginx`).
- cert-manager с ClusterIssuer `ingress.clusterIssuer` (по умолчанию `letsencrypt`). Без него очистите
  `ingress.clusterIssuer` и положите сертификат на все хосты в секрет `ingress.tlsSecret`.
- DNS-записи хостов `ingress.hosts.*` на адрес Ingress-контроллера.

## Секрет Keycloak
Секрет `keycloak.secretName` (по умолчанию `costguard-keycloak`) создаётся из Lockbox или Vault, как
`costguard-secrets` (см. [secrets.md](secrets.md)):

| Ключ | Что это |
|---|---|
| `KC_DB_URL` | `jdbc:postgresql://<хост>:6432/keycloak` — отдельная база Keycloak в том же кластере PostgreSQL |
| `KC_DB_USERNAME`, `KC_DB_PASSWORD` | пользователь этой базы |
| `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD` | первый администратор; после первого входа заведите именного администратора и удалите этого |
| `KEYCLOAK_PRIVACY_CLIENT_SECRET` | секрет клиента `costguard-privacy`; тот же ключ должен быть в `costguard-secrets` для ядра (см. [privacy.md](privacy.md)) |
| `KEYCLOAK_REGISTRATION_CLIENT_SECRET` | секрет клиента `costguard-registration`: ядро создаёт учётные записи при регистрации и приглашениях; тот же ключ — в `costguard-secrets` для ядра (см. [registration.md](registration.md)). Письма шлёт Keycloak: SMTP задаётся в консоли, Realm settings → Email |

## Включение
1. Создайте базу `keycloak` и секрет.
2. В values окружения: `ingress.enabled: true`, `keycloak.enabled: true`, `ingress.hosts.*` — реальные хосты.
3. Соберите образ фронтенда с адресом Keycloak: `--build-arg VITE_KEYCLOAK_URL=https://<ingress.hosts.keycloak>`.
4. После синхронизации Argo CD: `https://<keycloak>/realms/costguard/.well-known/openid-configuration` отвечает,
   в консоли есть realm `costguard` с клиентами `costguard-frontend`, `costguard-privacy` и `costguard-registration`.

Realm импортируется только при первом запуске: если он уже есть, импорт его не трогает. Поэтому правки в консоли
не пропадут после перезапуска, но и изменения `files/keycloak-realm.json` в уже работающий Keycloak сами не придут:
новую роль или клиента добавьте в консоли или через Admin API.

## Сервисные аккаунты компаний
Для коннекторов (роль `connector`, ADR 0030) и задачи прогнозов (роль `forecast`, ADR 0034) создайте в realm
клиента с `serviceAccountsEnabled`, назначьте его сервисному аккаунту роль и атрибут `tenant_id`, добавьте маппер
`tenant_id`, как у `costguard-frontend`. Секрет клиента положите туда, откуда его берёт Airflow
(`<TENANT>_CORE_TOKEN` получается по client credentials).

## Если Keycloak не стартует
- `startupProbe` падает больше пяти минут: смотрите логи. Чаще всего это недоступная база (`KC_DB_URL`) или
  неверный пароль.
- Реплики не видят друг друга (входы сбрасываются при переключении между подами): проверьте, что сервис
  `costguard-keycloak-headless` отдаёт адреса всех подов и что порт 7800 между ними открыт сетевыми политиками.
