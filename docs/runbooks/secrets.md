# Секреты

Все сервисы читают один секрет Kubernetes, `costguard-secrets` (`secretName` в values). Секретов нет ни в git,
ни в values.

## Что в нём
| Ключ | Откуда |
|---|---|
| `DB_USER`, `DB_PASSWORD` | `terraform output -json secrets` |
| `KAFKA_PASSWORD`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`, `REDIS_PASSWORD` | Там же |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Там же |
| `MLFLOW_BACKEND_STORE_URI` | Там же; нужен, когда `mlflow.enabled` (см. [mlflow.md](mlflow.md)) |
| `KEDA_KAFKA_SASL`, `KEDA_KAFKA_USERNAME`, `KEDA_KAFKA_TLS` | Только при `keda.kafkaAuth: true`: механизм (`scram_sha512`), пользователь Kafka (`costguard`), `enable`; пароль — `KAFKA_PASSWORD` |
| `JOURNAL_PRIVATE_KEY`, `JOURNAL_PUBLIC_KEY` | Ключ подписи квитанций, создаёт владелец (ниже); меняется раз в год |
| `JOURNAL_PREVIOUS_PUBLIC_KEYS` | Публичные ключи прежних ключей подписи через запятую: старые квитанции проверяются ими (ADR 0028) |
| `CONNECTIONS_ENCRYPTION_KEY` | `openssl rand -base64 32`, один раз, хранится вечно: им зашифрованы секреты подключённых систем, которые компания решила хранить в ядре (ADR 0030). Потерян — эти секреты придётся ввести заново |
| `AIRFLOW_URL`, `AIRFLOW_USERNAME`, `AIRFLOW_PASSWORD` | Необязательно: пользователь Airflow с правом запускать DAG `costguard_connector_sync`, чтобы «Синхронизировать сейчас» шло сразу (ADR 0019, дополнение) |
| `NEAR_ACCOUNT_ID`, `NEAR_PRIVATE_KEY` | Аккаунт нотариуса, `near generate-key` |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GIGACHAT_AUTH_KEY`, `YANDEX_API_KEY`, `YANDEX_FOLDER_ID` | Договоры с провайдерами |
| `KEYCLOAK_PRIVACY_CLIENT_SECRET` | Секрет клиента `costguard-privacy` в Keycloak окружения: ядро удаляет учётные записи при удалении данных (см. [privacy.md](privacy.md)) |
| `BACKUP_DB_USER`, `BACKUP_DB_PASSWORD` | `terraform output -json secrets`: роль `costguard_backup`, читает все компании (ADR 0025) |

Отдельный секрет `costguard-alerting`: `TELEGRAM_BOT_TOKEN`, `SMTP_PASSWORD` ([alerting.md](alerting.md)).

## Создать
```
terraform -chdir=deploy/terraform/yandex output -json secrets > /tmp/s.json
kubectl -n costguard create secret generic costguard-secrets --from-env-file=<(jq -r 'to_entries[]|"\(.key)=\(.value)"' /tmp/s.json) \
  --from-literal=JOURNAL_PRIVATE_KEY=... --from-literal=JOURNAL_PUBLIC_KEY=... --dry-run=client -o yaml | kubectl apply -f -
shred -u /tmp/s.json
```
В продакшене секрет лучше вести из Lockbox через External Secrets Operator, тогда ручного шага нет.

## Ротация
Решение: [ADR 0028](../adr/0028-secret-rotation.md). При утечке — сначала [key-leak.md](key-leak.md).

Новый `costguard-secrets` применяется сам: Deployment'ы помечены для Stakater Reloader (`reloadOnSecretChange`), и
сервисы перезапускаются после обновления секрета. Без Reloader — `kubectl -n costguard rollout restart deploy`.

| Секрет | Как часто | Как |
|---|---|---|
| Пароли PostgreSQL, `costguard_backup`, Kafka, ClickHouse, Redis | 180 дней, при утечке сразу | `secret_generation` +1 в tfvars окружения, `terraform plan` (меняются только `random_password` и пользователи), `apply` в окно обслуживания, обновить секрет из `terraform output -json secrets` |
| Ключ подписи квитанций | Раз в год | Ниже |
| Ключ нотариуса NEAR | Раз в год | Ниже |
| Ключи провайдеров ИИ | 90 дней или по правилам провайдера | Новый ключ у провайдера → секрет → шлюз перезапустился → в течение часа траты идут (`GatewayErrors` молчит) → отозвать старый |
| `KEYCLOAK_PRIVACY_CLIENT_SECRET`, секрет клиента smoke | Раз в год | Keycloak → Clients → Credentials → Regenerate → секрет |
| `TELEGRAM_BOT_TOKEN`, `SMTP_PASSWORD` | Раз в год | @BotFather `/revoke`, почтовый сервис → `costguard-alerting` |
| Ключ age резервных копий | Раз в год | `age-keygen`, новый `backups.ageRecipient`; старый закрытый ключ хранить ещё 30 дней (ADR 0025) |
| Статический ключ S3 | Раз в год | `terraform taint yandex_iam_service_account_static_access_key.storage`, `apply`, секрет |
| `ARGOCD_AUTH_TOKEN`, `SMOKE_PASSWORD` | Раз в год | Argo CD и Keycloak → секреты environment `staging` в GitHub |

### Ключ подписи квитанций
На машине владельца (ключ не покидает её, кроме как в секрет):
```
openssl genpkey -algorithm ed25519 -outform DER -out journal.key
base64 -w0 journal.key                                        # JOURNAL_PRIVATE_KEY
openssl pkey -inform DER -in journal.key -pubout -outform DER | base64 -w0   # JOURNAL_PUBLIC_KEY
shred -u journal.key
```
Прежний `JOURNAL_PUBLIC_KEY` дописать в `JOURNAL_PREVIOUS_PUBLIC_KEYS` (через запятую), затем заменить оба ключа в
секрете. Проверить: `GET /api/v1/journal/signing-keys` — новый ключ первым, прежний вторым; проверка цепочки любой
компании — `valid: true`, `unverifiedSignatures: 0`. Публичные ключи из `JOURNAL_PREVIOUS_PUBLIC_KEYS` не удаляют,
пока жива хоть одна квитанция, подписанная ими; закрытые уничтожают.

### Ключ нотариуса NEAR
У аккаунта NEAR может быть несколько ключей, поэтому замена без перерыва:
1. Добавить аккаунту нотариуса новый ключ через near-cli (`near account add-key`, точный синтаксис —
   `near account add-key --help`). Лучше ключ с доступом только к вызовам контракта нотариуса
   (`NEAR_CONTRACT_ID`), а не полный: им нельзя перевести деньги со счёта.
2. Новый закрытый ключ — в `NEAR_PRIVATE_KEY`; нотариус перезапустится.
3. Убедиться, что якоря идут: `NotaryAnchorsMissing` молчит, в журнале ядра появляются новые `anchor_batch`.
4. Удалить старый ключ с аккаунта (`near account delete-keys`).
