# Smoke-тест после выкладки

Решение: [ADR 0029](../adr/0029-post-deploy-smoke.md). Тест — `tests/smoke/test_staging.py`, запускает job
`smoke-staging` workflow `deploy-manifests` после успешной сборки `main`, когда Argo CD выкатил этот коммит.

## Настроить (один раз)
1. В Keycloak staging: клиент `costguard-smoke` (Client authentication off, Direct access grants on, мэппер
   `tenant_id` как у `costguard-frontend`); пользователь `smoke@…` с ролью `owner` и атрибутом `tenant_id` — новый
   UUID только для smoke. Пароль — длинный случайный.
2. Argo CD: проект `costguard`, роль только на чтение `applications, get, costguard/costguard-staging`, токен роли.
3. GitHub → Settings → Environments → `staging`:
   - Variables: `SMOKE_CORE_URL`, `SMOKE_GATEWAY_URL`, `SMOKE_KEYCLOAK_URL` (`https://…/realms/costguard`);
   - Secrets: `SMOKE_USERNAME`, `SMOKE_PASSWORD`, `ARGOCD_SERVER`, `ARGOCD_AUTH_TOKEN`.
4. Запустить вручную: Actions → deploy-manifests → Run workflow.

Локально на `make demo`: `SMOKE_CORE_URL=http://127.0.0.1:8080 SMOKE_GATEWAY_URL=http://127.0.0.1:8081
SMOKE_KEYCLOAK_URL=http://127.0.0.1:8180/realms/costguard SMOKE_CLIENT_ID=costguard-e2e SMOKE_USERNAME=demo
SMOKE_PASSWORD=demo uv run pytest tests/smoke`.

## Упал — на каком шаге
| Шаг | Сообщение | Куда смотреть |
|---|---|---|
| Вход | `sign-in failed: 401` | Пароль или клиент в Keycloak |
| Агент | `POST /api/v1/agents: 4xx` | Логи ядра; права роли smoke-пользователя |
| Шлюз | `Timed out … the gateway to know the new agent` | Шлюз не прочитал агента из Kafka: `OutboxStuck`, [outbox.md](outbox.md) |
| Проводка | `Timed out … the charge in the ledger` | Ядро не дочитало `agent.operations.v1`: [kafka-lag.md](kafka-lag.md) |
| Квитанция | `Timed out … the receipt` | Журнал ядра, ключ подписи |
| Excel | `the pipeline to read the file` / `failed` | `costguard-ingest`: логи, S3 |
| Находка | `the double charge to become a finding` | `costguard-anomaly`, топик `findings.v1` |

Job ждёт Argo CD до 20 минут; `staging runs <sha>, not <sha>` — синхронизация не дошла (ошибка Argo CD или
предыдущий коммит ещё выкатывается).
