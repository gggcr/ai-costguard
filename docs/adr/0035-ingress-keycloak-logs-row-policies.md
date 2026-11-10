# 0035. Инфраструктура: Ingress и Keycloak в кластере

Статус: принято, 2026-11-10.

## Контекст

На доске перед платформой стоит edge: Ingress и Keycloak в двух репликах. В чарте `deploy/helm/costguard` не было
ни Ingress, ни Keycloak, ни веб-клиента. Сервисы знали только адрес внешнего Keycloak (`external.keycloakIssuerUri`),
а Keycloak поднимался только в локальном стеке (`infra/local`, realm `infra/keycloak/costguard-realm.json`).

Пункт делается в два шага. Здесь первый — Ingress и Keycloak. Второй — логи в Loki и row policies в ClickHouse.

## Решение

**Веб-клиент в чарте.** Сервис `frontend` — это образ `apps/frontend` (nginx отдаёт сборку и проксирует `/api` в
ядро). Флаг `plain` у сервиса означает, что секрет и общие переменные в под не попадают: nginx они не нужны. Флаг
`writable` перечисляет каталоги, куда сервис пишет при read-only корне (nginx пишет в `/etc/nginx/conf.d` подстановку
своего шаблона). nginx не использует поисковые домены пода, поэтому ядро задано полным именем. Для этого значения
`env` в values теперь проходят через `tpl`, и в них можно писать `{{ .Release.Namespace }}`.

**Ingress (`templates/ingress.yaml`).** У каждого из четырёх хостов свой Ingress:
- `web` — веб-клиент, которому разрешены загрузки до `maxBodySize` и поток уведомлений без буферизации;
- `api` — API ядра для коннекторов, Airflow и скриптов;
- `gateway` — шлюз агентов без буферизации, с долгим таймаутом под потоковые ответы моделей;
- `keycloak` — если Keycloak ставит чарт.

TLS обязателен. С cert-manager у каждого хоста свой сертификат из ClusterIssuer `ingress.clusterIssuer`, без него
все хосты берут один секрет `ingress.tlsSecret`. По умолчанию Ingress выключен (`ingress.enabled`): пока у окружения
нет DNS и сертификатов, он только мешал бы синхронизации Argo CD.

**Keycloak (`templates/keycloak.yaml`).** Keycloak 26 в production-режиме за Ingress. TLS заканчивается на Ingress,
Keycloak доверяет заголовкам `X-Forwarded-*` (`KC_PROXY_HEADERS=xforwarded`). У него своя база в PostgreSQL. Health и
метрики на порту управления 9000, есть ServiceMonitor. Две реплики делят сессии через Infinispan, и находят друг
друга по DNS headless-сервиса (JGroups DNS_PING). Есть PodDisruptionBudget. Корень пода не read-only: при старте
Keycloak собирает конфигурацию в свои каталоги.

**Realm кластера.** `files/keycloak-realm.json` — это локальный realm без того, что нужно только локальному стеку:
- без клиента `costguard-e2e`, у которого вход по паролю;
- без локального секрета клиента `costguard-privacy` (`${KEYCLOAK_PRIVACY_CLIENT_SECRET}`);
- без адресов `localhost` (`${COSTGUARD_WEB_URL}`).

Подстановки заполняет сам Keycloak из переменных окружения при импорте. `sslRequired: external`, регистрация
выключена, защита от подбора пароля включена. Realm импортируется при первом запуске (`--import-realm`). Если realm
уже есть, импорт его не трогает, поэтому правки в консоли не теряются. Тест сверяет роли и мапперы клиентов с
локальным realm, чтобы новая роль (как `forecast` в ADR 0034) не появилась только в одном из них.

**Адрес Keycloak для сервисов.** Если `keycloak.enabled`, issuer и адрес Admin API выводятся из
`ingress.hosts.keycloak` (`https://<хост>/realms/costguard`), а `external.keycloak*` не читаются.

## Последствия

- Чтобы включить Ingress и Keycloak в staging и prod, нужны DNS, cert-manager и секрет `costguard-keycloak`
  ([docs/runbooks/keycloak.md](../runbooks/keycloak.md)).
- Адрес Keycloak во фронтенд попадает при сборке образа (`VITE_KEYCLOAK_URL`): образ собирается под окружение.
- Изменения realm в уже работающий Keycloak сами не приходят: их вносят в консоли или через Admin API.
- CI рендерит prod с включёнными Ingress и Keycloak и проверяет результат kubeconform.
