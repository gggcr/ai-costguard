# 0035. Инфраструктура: Ingress, Keycloak, логи в Loki, row policies в ClickHouse

Статус: принято, 2026-11-10.

## Контекст

На доске перед платформой стоит edge: Ingress и Keycloak в двух репликах. В чарте `deploy/helm/costguard` не было
ни Ingress, ни Keycloak, ни веб-клиента. Сервисы знали только адрес внешнего Keycloak (`external.keycloakIssuerUri`),
а Keycloak поднимался только в локальном стеке (`infra/local`, realm `infra/keycloak/costguard-realm.json`).

Логи сервисов печатались только в stdout подов. Хранилища логов и сборщика не было, а в реестре данных было
записано «сборщик логов платформы, вне репозитория». ClickHouse фильтровал компании только условием `tenant_id` в
каждом запросе, row policies (их требует доска) не было.

Пункт делался в два шага: сначала Ingress и Keycloak, затем логи и row policies.

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

**Логи в Loki.** `deploy/argocd/observability.yaml` — это проект Argo CD `observability` и два приложения
Grafana:
- Loki в режиме SingleBinary. Хранилище — бакет `logs` в регионе всех хранилищ (Terraform, `log_retention_days`,
  KMS). Срок хранения 30 дней, компактор удаляет старое, бакет удаляет на день позже.
- Grafana Alloy как DaemonSet. Он читает логи подов `costguard` и `costguard-staging` с меткой
  `app.kubernetes.io/part-of: costguard` и ставит метки `namespace`, `app`, `pod` и `level`. Уровень берётся из
  первого слова после времени, одинаково для строк Spring Boot и `costguard_common.logs`.

Поды сервисов ничего не знают о Loki: логи остаются в stdout, и маскирование ADR 0023 работает до того, как строка
куда-либо уходит. С `logging.enabled` чарт добавляет Loki как источник данных Grafana из kube-prometheus-stack
(ConfigMap с меткой `grafana_datasource`). Тест сверяет регион и endpoint Loki с Terraform и чартом, срок хранения —
с бакетом, пространства имён — с приложениями Argo CD.

**Row policies в ClickHouse.** `schemas/clickhouse/access/001_row_policies.sql`:
- роль `costguard_tenant_reader`, права только на `cost_records`, `agent_operations` и витрины над ними (сырые
  сообщения Kafka в `kafka_errors` ей недоступны), пользователь `costguard_reader`;
- политика `tenant_id = toUUIDOrZero(getSetting('SQL_tenant_id'))` на обе таблицы для этой роли. Читатель, который
  не назвал компанию, получает ошибку, а не все компании;
- политика `USING 1` для всех остальных. Таблица с политикой прячет строки от всех, кого политики не называют,
  поэтому ядро (оно сверяет бюджеты сразу по всем компаниям), бэкапы и materialized views без этой политики
  перестали бы видеть данные.

Политики применяет `apply.py` отдельным шагом (`apply_access`, журнал `schema_access`) и только с
`CLICKHOUSE_ACCESS_MANAGEMENT=1`: роли и политики может создать только пользователь с правом управлять доступом. В
Managed ClickHouse это право есть только при SQL-управлении пользователями. Оно необратимо и меняет способ ведения
пользователей в Terraform, поэтому в staging и prod политики выключены (`clickhouseMigrations.rowPolicies`) до
решения команды ([docs/runbooks/clickhouse-access.md](../runbooks/clickhouse-access.md)). Локально (`make clickhouse`)
и в CI политики включены. В CI ClickHouse запускается с правом управлять доступом, и тест проверяет: читатель видит
только свою компанию, в том числе через витрину, без компании получает ошибку, к `kafka_errors` не допущен, а
остальные видят всё. Задачи отчётов и прогнозов передают `SQL_tenant_id` в каждом запросе при
`CLICKHOUSE_TENANT_SETTING=1`. Ядро остаётся на пользователе без ограничений.

## Последствия

- Чтобы включить Ingress и Keycloak в staging и prod, нужны DNS, cert-manager и секрет `costguard-keycloak`
  ([docs/runbooks/keycloak.md](../runbooks/keycloak.md)).
- Адрес Keycloak во фронтенд попадает при сборке образа (`VITE_KEYCLOAK_URL`): образ собирается под окружение.
- Изменения realm в уже работающий Keycloak сами не приходят: их вносят в консоли или через Admin API.
- CI рендерит prod с включёнными Ingress и Keycloak и проверяет результат kubeconform.
- Для Loki нужен секрет `loki-s3` в пространстве имён `observability` с ключами сервисного аккаунта хранилища.
- Row policies защищают от ошибки в запросе отчёта или аналитика. Ядро по-прежнему фильтрует компании условием
  `tenant_id`: оно читает сразу все компании (сверка бюджетов) и не может работать под читателем одной компании.
