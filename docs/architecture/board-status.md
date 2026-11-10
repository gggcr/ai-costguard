# Сверка с доской архитектуры

Доска: [Miro «costguard»](https://miro.com/app/board/uXjVES7uH9I=/), фреймы 1–15 и «Правила репозитория».
Состояние ветки `feat/backlog-2026-11-08` на 2026-11-09. Это только описание: по итогам сверки ничего не исправлялось.

**Изменения после части 5 (сверка фреймов 2, 3, 8, 15), ADR 0020:**
- фрейм 2: обрыв потока сразу списывает полученное и возвращает остаток резерва, а не через уборщика;
- фрейм 3: одобрение — разовое разрешение на повтор со сроком (`approval-ttl`, по умолчанию час);
  второй повтор — `409 approval_used`, поздний — `403 approval_expired`;
- фрейм 8: экономию «по счёту» подтверждает человек (действия `confirm` и `missInvoice`), автоматической сверки
  со счетами нет — помечено «сделано иначе»;
- фрейм 15: находка по технике закрывается возвратом техники, действия по счёту для неё запрещены.

Статусы:
- **сделано** — как на доске;
- **сделано иначе** — работает, но устроено не так, как на доске. Со ссылкой на ADR, если решение записано,
  или с пометкой «ADR нет»;
- **не сделано** — в коде этого нет. «Частично» сказано в примечании.

Подробная сверка модуля `assets` (фреймы 9, 10, 12, 14, 15) — в [assets-vs-board.md](assets-vs-board.md).

## 1. Обзор платформы

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Шлюз агентов (Java, Spring Cloud Gateway): проверки и резерв до оплаты, LLM-прокси, `/v1/spend/authorize` | `apps/backend/gateway` (`api/`, `ladder/`, `providers/`) | сделано |
| Redis: счётчики бюджетов | `gateway/budget/BudgetReserve.java`, `src/main/resources/lua/` | сделано |
| Ingress и Keycloak | Helm: `templates/ingress.yaml`, `templates/keycloak.yaml`, `files/keycloak-realm.json`; локально `infra/keycloak`, `infra/local` | сделано (ADR 0035): чарт ставит Ingress (веб, API, шлюз, Keycloak) и Keycloak; включается в values окружения |
| Ядро на Spring Modulith: пользователи, роли, оргструктура, правила, бюджеты, мандаты, согласования, подписки, договоры, оборудование, импорт, журнал, находки | `apps/backend/core/src/main/java/ai/costguard/core/*` | сделано (ADR 0001) |
| PostgreSQL: справочники, журнал, состояния | Flyway `core/src/main/resources/db/migration` V1–V19, RLS | сделано (ADR 0002) |
| Kafka: операции агентов, риск-сигналы, правила; outbox ядра, квитанции; импорт, записи затрат, находки | `schemas/avro` (20 топиков), `core/shared/outbox`, `core/shared/inbox` | сделано (ADR 0003) |
| Детектор аномалий (Python): петли, всплески, подозрительные операции, риск 0–100 в шлюз | `apps/python/anomaly` | сделано иначе: папка `apps/python/anomaly`, а не `apps/backend/detector`, ADR нет. Устройство по ADR 0016 |
| Нотариус NEAR (Python, py-near): пакеты квитанций, дерево Меркла | `apps/python/notary` | сделано иначе (ADR 0006): свой RPC-клиент `near.py` вместо py-near, папка `apps/python/notary` |
| Смарт-контракт NEAR `costguard-notary` | `contracts/notary` | сделано иначе (ADR 0006, 0009): папка `contracts/notary`, а не `smart-contracts/costguard-notary` |
| Конвейер приёма: Excel, CSV, PDF, выписки, проверки, ручная проверка, нормализация в FOCUS | `apps/python/ingest`, `core/imports` | сделано (ADR 0012, 0013) |
| Коннекторы и агент у клиента: единые API и свои для 1С, Яндекс 360 | `apps/python/connectors` | сделано (ADR 0014, 0015, 0019) |
| Движок аудита (Python): правила, находки, оценка экономии | `core/findings` + `AuditRules` в `software` и `assets` | сделано иначе (ADR 0005): в ядре, а не отдельным Python-сервисом |
| Прогнозы и отчёты (Python): расходы, экономия, сценарии, XLSX и PDF | `apps/python/reports` (`models`, `backtest`, `baseline`, `reconcile`, `savings`, `publish`, `report`), сценарии в `core/analytics` | сделано иначе (ADR 0034): модели на чистом Python вместо statsforecast; сценарии считает ядро |
| MLflow: модели прогнозов | `deploy/mlflow`, Helm `mlflow.yaml` | сделано иначе (ADR 0016, 0017): в MLflow лежат модели детектора аномалий, моделей прогноза там нет |
| ClickHouse: единый реестр затрат, витрины | `schemas/clickhouse/migrations` 001–003 | сделано (ADR 0007) |
| S3 (MinIO): исходные файлы, отчёты | SeaweedFS в `infra/local`, бакеты в Terraform | сделано иначе, ADR нет: локально SeaweedFS вместо MinIO. В ядре файлы импорта пишутся на локальный диск (`FileStore`) |
| Фронтенд: отдельная команда, REST API и SSE | `apps/frontend` (`api/remote*.ts`, `api/api-store.tsx`, `auth/oidc.ts`), `schemas/openapi`, `/api/v1/notifications/stream` | сделано: вход через Keycloak (PKCE), все страницы на API ядра, уведомления по SSE; журнал операций — `GET /api/v1/operations` из ClickHouse |
| Синхронно в момент операции работают только шлюз и Redis | Лестница шлюза; ядро получает операции из `agent.operations.v1` | сделано (ADR 0004) |

## 2. Путь операции агента

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Запрос с виртуальным ключом в шлюз | `gateway/filters/IdentityFilter.java`, `keys/AgentDirectory.java` | сделано |
| Ключ, мандат, повторы, риск, оценка цены до вызова | `filters/*`, `metering/` (jtokkit), `rules/PriceBook.java` | сделано |
| Резерв на всех уровнях бюджета атомарно (Lua) | `budget/BudgetReserve.java`, `budget/BudgetTree.java`, `resources/lua` | сделано |
| Вызов провайдера с `max_tokens` в пределах остатка | `providers/MeteredCallFilter.java`, `Dialect.maxTokens` | сделано |
| Ответ потоком, списание факта и возврат остатка резерва | `MeteredCallFilter`, `BudgetReserve`, `ReservationSweeper` (уборка брошенных резервов) | сделано; обрыв потока агентом списывает полученное и сразу возвращает остаток (ADR 0020) |
| Событие операции асинхронно в Kafka → журнал и квитанция, реестр затрат, детектор, NEAR | `gateway/events` → `agent.operations.v1` → `core/shared/inbox` (ledger, journal), `cost.records.v1`, `apps/python/anomaly`, `journal.receipts.v1` → нотариус | сделано |
| Денег не хватает: отказ с уровнем и остатком, `402 budget_exceeded` | `filters/BudgetFilter.java` | сделано |

## 3. Лестница проверок шлюза

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Ступени по порядку: 1 ключ и агент, 2 мандат, 3 повторы и петли, 4 риск, 5 резерв, 6 ИИ-оценщик, 7 согласование | `gateway/filters/{Identity,Mandate,Loop,Risk,Budget,TaskFit,Approval}Filter.java`, `ladder/CheckLadder` | сделано |
| Повтор или петля → ответ из кэша или `409 duplicate_operation` | `LoopFilter.java` | сделано |
| Риск проверяется до резерва | `RiskFilter` (4) перед `BudgetFilter` (5) | сделано |
| ИИ-оценщик сам пропускает крупную операцию, если она соответствует цели задачи, иначе решает человек | `approvals/LlmTaskFitAssessor.java`, `HumanOnlyAssessor.java`, заголовок цели задачи в SDK | сделано (ADR 0011) |
| Согласование человеком; одобренная операция повторяется и снова проходит все проверки, включая резерв | `ApprovalFilter`, `approvals/ApprovalBoard.java` ← `approvals.decisions.v1` из `core/approvals` | сделано иначе, ADR нет: решения идут через топик `approvals.decisions.v1`, которого на доске нет |
| Разрешение на повтор одноразовое и с ограниченным сроком | `ApprovalFilter` (Redis `SET NX`, `costguard.gateway.approval-ttl`) | сделано (ADR 0020) |
| Платные внешние API через шлюз | `gateway/egress` | сделано (ADR 0010) |

## 4. Детектор аномалий

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Читает `agent.operations.v1` из Kafka | `apps/python/anomaly/.../service.py` | сделано |
| Признаки: окна 1 и 5 минут, по задаче и агенту | `risk.py` | сделано |
| Петли: похожие запросы (SimHash), рост контекста, число шагов | `risk.py` (`prompt_simhash`) | сделано |
| Скорость трат против базовой линии агента (EWMA) | `risk.py` | сделано |
| Подозрительное: новый поставщик, дробление, ночь, ошибки | `risk.py` | сделано |
| Isolation Forest и онлайн-модели river | `models.py`, модели в MLflow | сделано (ADR 0016) |
| Риск 0–100 с объяснением → порог → агент остановлен, `423` | `scorer.py` (50/70/90, STOP только по правилу), `gateway/risk/RiskBoard.java`, `RiskFilter` | сделано (ADR 0016) |
| `agent.risk-signals.v1` (compacted) → шлюз | `gateway/risk/RiskBoard.java` | сделано |
| `agent.risk-signals.v1` → ядро agents и notifications: уведомление владельцу агента | `shared/inbox/RiskSignalInbox`, `agents/internal/RiskService` (`agent_risk`), `notifications/internal/NotificationTriggers` | сделано (ADR 0031): риск и объяснение в карточке агента, уведомление при смене действия владельцу агента, владельцам и ИТ |
| `batch/`: платежи за ПО, двойные списания | `detector.py` (`anomaly.spike` → `findings.v1`) | не сделано: есть только правило всплеска трат по провайдеру, двойных списаний нет |

## 5. Подтверждение операций в NEAR

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Нотариус читает `journal.receipts.v1` | `apps/python/notary/.../service.py` | сделано |
| Пакет за 60 с или 10 000 квитанций, дерево Меркла и пути проверки | `batches.py`, `merkle.py` | сделано |
| Ключ Function-Call, вызов контракта | `near.py` | сделано иначе (ADR 0006): свой клиент NEAR RPC вместо py-near |
| `anchor_batch` и `journal.anchors.v1` | `notarize.py`; в контракте метод `anchor` | сделано иначе (ADR 0006): методы называются `anchor`/`get_anchor`, а не `anchor_batch`/`get_batch` |
| `register_mandate`, `get_mandate`, `confirm_operation` | `contracts/notary/src/lib.rs`, `notary.requests.v1` / `notary.confirmations.v1` | сделано (ADR 0009) |
| В NEAR только корень пакета, без сумм и персональных данных | `merkle.py`, контракт хранит хэши | сделано |
| Контракт: `batches.rs`, `mandates.rs`, `approvals.rs`, тесты near-workspaces в sandbox, сборка cargo-near | `contracts/notary/src/lib.rs` | сделано иначе, ADR нет: всё в одном `lib.rs`, unit-тесты `cargo test` вместо near-workspaces |
| Ядро принимает якоря обратно | `core/shared/inbox` слушает `journal.anchors.v1` | сделано |

## 6. Конвейер данных контура Б

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Учёт, кадры, SSO, банки, облака → коннекторы, агент у клиента, Airflow | `apps/python/connectors`, `infra/airflow/dags/connectors_hourly.py` | сделано |
| Excel, CSV, выписки, PDF, счета с почты → API импорта ядра | `core/imports`, `connectors/mail.py` | сделано |
| Bronze: файл в S3 по sha256 | `core/imports` (`tenant/sha256`), `ingest/storage.py` | сделано иначе, ADR нет: в ядре файлы лежат на локальном диске, S3 — только у ingest |
| Разбор: шаблон, поиск колонок, OCR, LLM | `ingest/mapping.py`, `tables.py`, `ocr.py`, `extraction.py` | сделано (ADR 0012, 0013) |
| Проверки пройдены и уверенность выше порога? Нет → ручная проверка, исправления становятся шаблоном | `ingest.results.v1` → `core/imports` (подтверждение сопоставления), шаблоны поставщика | сделано |
| Нормализация и склейка: поставщик, сотрудник, валюта, период → `ingest.normalized.v1` → справочники ядра | `costguard_common.normalized`, `core/imports` `NormalizedEntities` | сделано (ADR 0008) |
| Silver: записи затрат FOCUS в ClickHouse; сюда же операции агентов из контура А | `schemas/clickhouse/001_cost_records.sql`, `002_agent_operations.sql`, `cost.records.v1` | сделано (ADR 0007) |
| Сопоставление: счёт, подписка, договор, сотрудник | `core/imports` (`EntityStaging`, лицензии и подписки) | сделано иначе, ADR нет: сопоставление идёт в PostgreSQL ядра, а не между Silver и Gold в ClickHouse |
| Gold: витрины | `003_spend_views.sql` | сделано |
| Движок аудита → находки и экономия | `core/findings` | сделано иначе (ADR 0005) |
| Прогнозы; отчёты XLSX и PDF | `apps/python/reports` | сделано иначе (ADR 0034): прогноз расходов моделью-чемпионом бэктеста, прогноз экономии по находкам, отчёт |

## 7. Парсинг отчётов

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Маршрут по типу файла | `ingest/documents.py`, `pipeline.py` | сделано |
| Excel по шаблону; Excel в свободной форме: заголовки, синонимы, LLM | `mapping.py`, `tables.py`, `examples.py` | сделано |
| PDF с текстом (pdfplumber) | `documents.py` | сделано |
| Сканы (PaddleOCR) | `ocr.py` | сделано иначе (ADR 0012): Tesseract вместо PaddleOCR |
| LLM: поля счёта в JSON-схему | `extraction.py`, вызов через шлюз | сделано (ADR 0013) |
| Банковские выписки: 1CClientBankExchange, MT940 | `statements.py`, `mt940.py` (и ещё `camt053.py`, `ofx.py`) | сделано |
| Проверки: суммы, ИНН, даты, дубли (pandera) | `values.py`, `pipeline.py` | сделано иначе, ADR нет: свои проверки без pandera |
| Сервис на FastStream | `service.py` на confluent-kafka | сделано иначе, ADR нет |
| Низкая уверенность → человеку, исправления становятся шаблоном поставщика | `ingest.results.v1`, `core/imports` | сделано |
| Нормализация валюты по курсу ЦБ | `core/catalog/internal/CbrRatesLoader.java` | сделано |

## 8. Жизненный цикл находки

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Состояния: новая, на проверке, принята, выполняется, отклонена, отложена, закрыта автоматически, подтверждена | `core/findings/internal/FindingModel.java` (`FindingStatus`) | сделано |
| Переходы: назначен владелец, принять, отклонить с причиной, отложить до даты, создана задача | `FindingAction`, `FindingTimer` (наступила дата) | сделано |
| Условие исчезло → закрыта автоматически | `AuditEngine` (`AUTO_CLOSABLE`) | сделано |
| Экономия засчитывается, когда видна в следующих счетах; не видна два счёта подряд → обратно на проверку | `software/internal/InvoiceBook` → `FindingsIntake.invoiced`; ручные `confirm`, `missInvoice` | сделано (ADR 0033): новый счёт по подписке сравнивается с последним счётом до задачи; находки по счетам без подписки подтверждает человек |
| Отпечаток находки: правило и сущности | `fingerprint` в `finding` | сделано |
| Правила: неиспользуемые лицензии, лицензии уволенных, дубли подписок, скорое автопродление | `license.unused`, `license.dismissed`, `subscription.duplicate`, `contract.renewal` | сделано |
| Правила: пересекающиеся инструменты, избыточный тариф, ошибки в счетах, теневые подписки, ИИ-расходы мимо шлюза, помесячно вместо годового | `subscription.overlap`, `subscription.oversized-plan`, `subscription.monthly-billing` (`software`); `invoice.duplicate`, `invoice.overcharge`, `vendor.shadow`, `ai.bypass` (`imports`, `SpendAuditRules`) | сделано (ADR 0032): условия выбраны нами, на доске только названия; «ошибки в счетах» — два правила; избыточный тариф и годовая цена — по каталогу тарифов `product_plan` |

## 9. Модель данных

| Элемент доски | Где в коде | Статус |
|---|---|---|
| TENANT, USER_ACCOUNT, DEPARTMENT, EMPLOYEE | `V1__org_tenant.sql`, `core/org` | сделано |
| POLICY | `core/policies` | сделано |
| AGENT, MANDATE, VIRTUAL_KEY | `core/agents` | сделано |
| OPERATION, BUDGET, LEDGER_ENTRY | `core/ledger`, ClickHouse `agent_operations` | сделано |
| APPROVAL_REQUEST | `core/approvals` | сделано |
| RECEIPT, ANCHOR_BATCH | `core/journal` | сделано |
| VENDOR, PRODUCT, CONTRACT, PRICE, SUBSCRIPTION, LICENSE_ASSIGNMENT, ACTIVITY_EVENT | `core/software` | сделано |
| INVOICE, INVOICE_LINE, PAYMENT | `core/software` (`invoice`, `invoice_line`, `payment`, V25), `ingest.normalized.v1` | сделано (ADR 0033): счёт привязан к подписке, платёж — к счёту |
| FX_RATE | `core/catalog` (курсы ЦБ) | сделано |
| IMPORT_FILE, SOURCE_TEMPLATE | `core/imports` | сделано |
| FINDING, SAVINGS_RECORD | `core/findings` | сделано |
| ASSET, ASSET_ASSIGNMENT | `core/assets`, `V2__assets.sql` | сделано; отличия в [assets-vs-board.md](assets-vs-board.md) |
| COST_RECORD в ClickHouse | `schemas/clickhouse/001_cost_records.sql` | сделано |
| У каждой записи есть `tenant_id` | все таблицы ядра + RLS; ClickHouse по `tenant_id` | сделано (ADR 0002) |
| CONNECTION, CONNECTION_RUN | `V19__connections.sql` | сделано иначе (ADR 0019): на доске их нет |

## 10. Топики Kafka

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Схемы в Avro и Schema Registry, outbox Spring Modulith | `schemas/avro`, `core/shared/outbox`, контрактные тесты | сделано (ADR 0003, 0018) |
| `policy.snapshots.v1`, `catalog.prices.v1` ядро → шлюз | `EventMapping`, `gateway/rules` | сделано |
| `agents.state.v1`, `budgets.limits.v1` ядро → шлюз | `EventMapping`, шлюз | сделано |
| `agent.operations.v1` шлюз → ядро (ledger и journal), детектор, ClickHouse | `gateway/events`, `core/shared/inbox`, `apps/python/anomaly` | сделано |
| `agent.risk-signals.v1` детектор → шлюз | `gateway/risk` | сделано |
| `agent.risk-signals.v1` детектор → ядро: agents и notifications | `InboxConfig` (группа `core-risk`) | сделано (ADR 0031) |
| `journal.receipts.v1` → нотариус → `journal.anchors.v1` → ядро | `core/journal`, `apps/python/notary`, `core/shared/inbox` | сделано |
| `cost.records.v1` → сервис прогнозов и ClickHouse | ClickHouse (ADR 0007); `apps/python/reports` читает ClickHouse, а не топик | сделано иначе (ADR 0007) |
| `ingest.files.v1` ядро → конвейер приёма; `ingest.normalized.v1` → ядро: software и org | `core/imports`, `apps/python/ingest` | сделано |
| `findings.v1` движок аудита → ядро: findings и notifications | `core/shared/inbox` ← `apps/python/anomaly` | сделано иначе (ADR 0005): топик пишет детектор, движок аудита работает внутри ядра |
| Топики, которых нет на доске: `ingest.accepted.v1`, `ingest.results.v1`, `notary.requests.v1`, `notary.confirmations.v1`, `approvals.decisions.v1`, `assets.assignments.v1`, `connectors.config.v1`, `connectors.runs.v1` | `schemas/avro`, `schemas/avro/README.md` | сделано иначе (ADR 0009, 0013, 0019); для `ingest.results.v1`, `approvals.decisions.v1`, `assets.assignments.v1` ADR нет |

## 11. Развёртывание

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Kubernetes, 3 зоны доступности | `deploy/terraform/yandex/main.tf` (`var.zones`, группы узлов по зонам) | сделано |
| edge: Ingress, Keycloak ×2 | `ingress.*`, `keycloak.*` в `values.yaml` (две реплики Keycloak с общими сессиями) | сделано (ADR 0035), в staging и prod пока выключено: нужны DNS, сертификаты и секрет |
| core: ядро ×2+, проверяет токены Keycloak | Helm `workloads.yaml`, `core/shared/security` | сделано |
| data-plane: шлюз ×3+, автомасштабирование | Helm, HPA в `workloads.yaml` | сделано |
| analytics: Airflow → Python-воркеры с KEDA → MLflow | `keda.yaml`, `mlflow.yaml`, `infra/airflow` | сделано (ADR 0017) |
| observability: Prometheus, Grafana, Loki | `monitoring.yaml` (PrometheusRule, ConfigMap с дашбордом Grafana), `/actuator/prometheus` | не сделано (частично): алерты и дашборд есть, сами Prometheus, Grafana и Loki чарт не ставит, OpenTelemetry Collector нет |
| Kafka 3 брокера, Redis Cluster, PostgreSQL лидер + 2 реплики, ClickHouse 2 реплики, S3 | Terraform: `yandex_mdb_kafka_cluster`, `yandex_mdb_redis_cluster`, `yandex_mdb_postgresql_cluster`, `yandex_mdb_clickhouse_cluster`, `yandex_storage_bucket` | сделано (управляемые сервисы; число хостов в prod задаётся по зонам) |
| Vault | `infra/local` (vault), ключи провайдеров `gateway/keys`, ключ подписи квитанций `core/journal/ReceiptSigner` | сделано |
| Внешние: провайдеры ИИ, NEAR RPC | `gateway/providers`, `apps/python/notary/near.py` | сделано |

## 12. Структура репозитория

| Элемент доски | Где в коде | Статус |
|---|---|---|
| `apps/backend/gateway`, `apps/backend/core` | там же | сделано |
| `apps/backend/{detector,notary,ingest,connectors,audit,forecast}` | `apps/python/{anomaly,notary,ingest,connectors,reports}`; audit в ядре | сделано иначе: Python-сервисы лежат в `apps/python` (ADR нет); audit в ядре (ADR 0005); forecast — `reports` |
| `apps/backend/airflow` | `infra/airflow` | сделано иначе, ADR нет |
| `apps/frontend` — зона фронтенд-команды | `apps/frontend`, строка в `CODEOWNERS` с плейсхолдером владельца | сделано |
| `smart-contracts/costguard-notary` | `contracts/notary` | сделано иначе, ADR нет |
| `libs/java`: platform-starter (tenant и RLS, RFC 9457, трассировка), events | `libs/java/platform-starter`, `libs/java/events` | сделано |
| `libs/python`: costguard-common, costguard-events, costguard-storage, costguard-llm | `libs/python/costguard-common` (схемы, wire, normalized, payments) | сделано иначе, ADR нет: одна библиотека вместо четырёх |
| `sdk/python` (costguard-sdk), `sdk/js` (@costguard/sdk) | `libs/python/costguard-sdk`, `libs/js/costguard-sdk` | сделано иначе, ADR нет: в `libs/`, а не в `sdk/` |
| `schemas/avro` («9 топиков»), `schemas/openapi`, `schemas/clickhouse` (row policies по tenant_id) | `schemas/*`, 20 топиков | сделано; row policies в ClickHouse — не сделано (запросы фильтруют по `tenant_id`) |
| `infra/local`: docker-compose с PostgreSQL, Redis, Kafka, ClickHouse, MinIO, Keycloak, Vault | `infra/local/docker-compose.yml` | сделано иначе, ADR нет: SeaweedFS вместо MinIO |
| `infra/helm`, `infra/argocd`, `infra/terraform` | `deploy/helm`, `deploy/argocd`, `deploy/terraform` | сделано иначе, ADR нет: папка `deploy/` |
| `infra/keycloak`: realm и роли owner, finance, it_admin, agent_owner, auditor | `infra/keycloak/costguard-realm.json` | сделано |
| `infra/observability`: дашборды Grafana, алерты, OpenTelemetry Collector | `deploy/helm/costguard/dashboards`, `monitoring.yaml` | не сделано (частично): нет папки и Collector |
| `tests/e2e`: лимит агента и ответ 402, Excel и находки | `tests/e2e/test_money_path.py` | не сделано (частично): есть путь денег, 402 и находки аудита по демо-данным; загрузки Excel в сценарии нет |
| `tests/load` (k6), `tests/contract` | `tests/load`, `tests/contracts` | сделано |
| `docs/adr`, `docs/runbooks` (Redis, Kafka, ядро, провайдер), `docs/architecture` | `docs/*` | сделано; ранбука по Redis и провайдеру нет, есть gateway, kafka-lag, core, notary, mlflow, secrets, deploy, terraform |
| `tools/codegen`, `tools/seed` | там же | сделано |
| Файлы в корне: README.md, Makefile, .github/workflows, CODEOWNERS, settings.gradle.kts, gradle/libs.versions.toml, pyproject.toml и uv.lock, .env.example | корень репозитория | сделано |

## 13. Внутри сервисов

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Шлюз: `api/`, `providers/` (OpenAI, Anthropic, GigaChat, YandexGPT), `egress/`, `ladder/`, `filters/` (7 ступеней), `rules/` (CEL), `budget/`, `metering/` (jtokkit), `risk/`, `keys/` (Vault), `events/` (Kafka и буфер на диске) | `apps/backend/gateway/src/main/java/ai/costguard/gateway/*` | сделано; дополнительно `approvals/` (ADR 0011) |
| Ядро: `org`, `agents`, `policies`, `ledger`, `catalog`, `approvals` (эскалация по таймеру), `journal` (Ed25519, ключ из Vault), `software`, `assets`, `imports`, `findings`, `analytics`, `notifications`, `shared` | `apps/backend/core/src/main/java/ai/costguard/core/*` | сделано; дополнительно модуль `connections` (ADR 0019) |
| Ядро `analytics/`: прогнозы, сценарии, отчёты | `core/analytics`: `GET/PUT /api/v1/analytics/forecast`, `POST /api/v1/analytics/scenarios`, `summary` | сделано (ADR 0034); отчёты XLSX/PDF по-прежнему строит `costguard-report` |
| Ядро `notifications/`: Telegram, почта, поток SSE | `core/notifications` | сделано (ADR 0031): лента и SSE; почта (SMTP) и Telegram-бот через очередь доставки в PostgreSQL с повторами; у каждого пользователя свои уровни, Telegram привязывается одноразовой ссылкой |
| Flyway V1 tenant и RLS; тест границ модулей | `V1__org_tenant.sql`, `ModuleBoundariesTest` | сделано |
| Детектор: `features/` (окна, SimHash, EWMA), `detectors/` (loops, velocity, suspicious, iforest, drift), `scoring.py`, `batch/` | `apps/python/anomaly`: `risk.py`, `models.py`, `scorer.py`, `detector.py` | сделано иначе (ADR 0016): другая раскладка файлов; `batch/` двойных списаний нет |
| Нотариус: `main.py`, `batcher.py`, `merkle.py`, `near_client.py`, `anchors.py`, `mandates.py`, тесты в near-sandbox | `service.py`, `batches.py`, `merkle.py`, `near.py`, `notarize.py`; тесты на фейковом клиенте | сделано иначе (ADR 0006, 0009) |
| Ingest: `bronze`, `parsers/` (excel_template, excel_freeform, pdf_text, ocr, bank_1c, mt940), `extract/`, `validate/`, `normalize/`, `review/`, `sinks/`, `tests/fixtures` | `apps/python/ingest/src/costguard_ingest/*` плоско | сделано иначе (ADR 0012, 0013): плоская раскладка, Tesseract, без FastStream и pandera |
| Audit: `engine.py`, 10 правил, `fingerprint.py`, `savings.py`, `publish.py` | `core/findings` + правила в модулях | сделано иначе (ADR 0005); все 10 правил есть (ADR 0032, см. фрейм 8) |
| Forecast: baseline, ETS и Theta (statsforecast), расходы агентов (LightGBM), MinT, savings, scenarios, backtest (WAPE, MASE), `reports/` с пояснениями LLM, еженедельное переобучение в MLflow | `apps/python/reports`: `baseline.py`, `models.py` (наивный, сезонный, Хольт, ETS(A,Ad,N), Theta), `backtest.py`, `reconcile.py`, `savings.py`, `publish.py`; сценарии в ядре; `forecast_weekly` в Airflow | сделано иначе (ADR 0034): свои модели вместо statsforecast, без LightGBM и пояснений LLM; переобучение — еженедельный запуск, модели в MLflow не хранятся |
| У каждого сервиса свой Dockerfile | Java-сервисы — свой; Python — общий `apps/python/Dockerfile` | сделано иначе, ADR нет |

## 14. Интеграции с системами компании

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Не переносить, а синхронизировать; сначала только читать | все коннекторы только читают | сделано |
| Уровень 1: счета на почту, стандартные выписки банков, Excel | `connectors/mail.py`, `ingest/statements.py`, `mt940.py`, `camt053.py`, `ofx.py`, импорт Excel | сделано |
| Уровень 1: FOCUS и SCIM | — | не сделано: приёма FOCUS-файлов и SCIM нет |
| Уровень 2: единый API для учёта, кадров и банков | `connectors/merge.py` | сделано (ADR 0014) |
| Уровень 3: 1С, МойСклад, Bitrix24, Яндекс 360 | `onec.py`, `moysklad.py`, `bitrix24.py`, `yandex360.py` | сделано |
| SSO и учётные записи: Google Workspace, Okta, Entra ID | `google_workspace.py`, `okta.py`, `entra.py` | сделано |
| Банки напрямую | `tbank.py`, `banks.py` | сделано (ADR 0015) |
| Системы на серверах клиента читает агент, наружу ничего не открывается | `connectors/agent.py` (только исходящие к Kafka) | сделано |
| Склейка поставщиков и людей | `costguard_common.normalized`, `core/imports` | сделано (ADR 0008) |
| Новый коннектор только переводит формат в единую модель | `connectors/contract.py` | сделано |
| Страница «Подключённые системы» | API `core/connections`, ТЗ для фронтенда в [connections-page.md](connections-page.md) | сделано иначе (ADR 0019): на доске нет; страница `apps/frontend/src/pages/Connections.tsx` работает с API, секреты вводятся при подключении |

## 15. Учёт оборудования сотрудников

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Таблицы оборудования и выдачи | `core/assets`, `V2__assets.sql` | сделано |
| Увольнение показывает всё, что числится за сотрудником | `GET /api/v1/employees/{employeeId}/assets`, `EmployeeDismissed` | сделано |
| Находка по технике закрывается возвратом (`assets.assignments.v1`), а не счётом | `assets/internal/ReturnedEquipment`, `FindingsIntake.settled` | сделано (ADR 0020): закрытие в той же транзакции, что и событие |
| Находки: не сдал технику, срок возврата прошёл, техника лежит на складе, два ноутбука у сотрудника | правила `asset.duplicate`, `asset.idle` | не сделано (частично): «не сдал уволенный» и «срок возврата прошёл» отдельными правилами не сделаны |
| Загрузка из Excel, 1С, Jamf, Intune | `jamf.py`, `intune.py`; `POST /api/v1/assets` | не сделано (частично): из 1С и Excel техника не загружается |
| Не склад и не учёт ОС; удержания решают кадры; командировочные не входят | — | сделано (ничего лишнего нет) |

Подробности — в [assets-vs-board.md](assets-vs-board.md).

## Ключевые решения

| Элемент доски | Где в коде | Статус |
|---|---|---|
| Интеграции через единую модель данных | `connectors/contract.py`, `ingest.normalized.v1` | сделано (ADR 0008, 0014) |
| В NEAR только корень пакета квитанций | `apps/python/notary`, `contracts/notary` | сделано (ADR 0006) |
| ClickHouse для фактов, PostgreSQL для справочников | `schemas/clickhouse`, Flyway | сделано (ADR 0007) |
| Kafka между контурами | `schemas/avro` | сделано (ADR 0003) |
| Счётчики в Redis, источник истины в PostgreSQL; после сбоя счётчики восстанавливаются из журнала проводок | `gateway/budget`, `core/ledger` | не сделано (частично): резерв в Redis есть, восстановления счётчиков из журнала проводок нет |
| Шлюз агентов в разрыве трафика | `apps/backend/gateway` | сделано (ADR 0004) |
| Ядро — модульный монолит на Spring Modulith | `apps/backend/core`, `ModuleBoundariesTest` | сделано (ADR 0001) |

## Правила репозитория

| Элемент доски | Где в коде | Статус |
|---|---|---|
| `make up` поднимает инфраструктуру из `infra/local`, `make demo` загружает демо-компанию из `tools/seed`, `make test` запускает тесты всех языков | `Makefile` (`up`, `demo`, `seed`, `test`) | сделано |
| Фронтенд связан с бэкендом только контрактом; клиент генерируется из той же спецификации | `tools/codegen/openapi_ts.py` → `apps/frontend/src/api/generated` | сделано |
| `apps/frontend` принадлежит фронтенд-команде | `CODEOWNERS` | сделано иначе: владелец — плейсхолдер `@frontend-owner-placeholder`, его нужно заменить на реальный логин |
| CI по изменённым путям; правка в `schemas/` запускает всех, кто использует схему | `paths` в `.github/workflows/*.yml`, `contracts.yml` | сделано (у `load.yml` фильтра нет: запускается вручную или по расписанию) |
| Argo CD разворачивает готовые образы по окружениям | `deploy/argocd/{staging,prod}.yaml`, `deploy.yml` | сделано иначе, ADR нет: окружения staging и prod, отдельного dev нет |
| Границы модулей ядра проверяет тест; модули общаются через события | `ModuleBoundariesTest` | сделано |
| События Kafka: сначала схема Avro, классы Java и Python генерируются | `schemas/avro`, `libs/java/events`; Python читает `.avsc` во время работы | сделано иначе (ADR 0003): Python-классы не генерируются |
| REST: OpenAPI выгружается из кода, CI сравнивает с прошлой версией и останавливает несовместимые изменения | `OpenApiContractTest`, `oasdiff breaking` в `contracts.yml` | сделано |
| Java: один Gradle-проект, версии в `gradle/libs.versions.toml`; Python: workspace uv с общим `uv.lock`; Rust: cargo в smart-contracts | `settings.gradle.kts`, `gradle/libs.versions.toml`, `pyproject.toml`, `uv.lock`, `contracts/notary/Cargo.toml` | сделано (Rust в `contracts/`, а не `smart-contracts/`) |
| Один сервис — одна папка: код, тесты, Dockerfile и README; владелец в CODEOWNERS | Java-сервисы — да. Python: общий Dockerfile, README только у `connectors`; в CODEOWNERS нет отдельных строк для сервисов | не сделано (частично) |

## Коротко: что расходится с доской

Не сделано:
- двойные списания за ПО в детекторе (`batch/`);
- в прогнозах нет LightGBM для агентов и пояснений LLM в отчётах (ADR 0034);
- восстановление счётчиков Redis из журнала проводок;
- Prometheus, Grafana, Loki, OpenTelemetry Collector, `infra/observability`;
- row policies в ClickHouse;
- FOCUS и SCIM на уровне 1 интеграций;
- техника из 1С и Excel, правила «уволенный не сдал технику» и «срок возврата прошёл»;
- загрузка Excel в e2e-сценарии; ранбуки по Redis и провайдеру;
- README и Dockerfile у каждого Python-сервиса, владельцы сервисов в CODEOWNERS.

Сделано иначе, ADR нет:
- раскладка папок: `apps/python/*` вместо `apps/backend/*`, `infra/airflow`, `contracts/notary`, `deploy/*`,
  SDK в `libs/`, одна `costguard-common` вместо четырёх библиотек;
- SeaweedFS вместо MinIO; файлы импорта ядра на локальном диске;
- ingest без FastStream и pandera;
- контракт NEAR в одном `lib.rs` с unit-тестами вместо near-workspaces;
- топики `approvals.decisions.v1`, `assets.assignments.v1`, `ingest.results.v1`;
- сопоставление счетов и подписок в PostgreSQL ядра, а не в ClickHouse;
- окружения Argo CD: staging и prod, без dev.
