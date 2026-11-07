# Секреты

Все сервисы читают один секрет Kubernetes, `costguard-secrets` (`secretName` в values). Секретов нет ни в git,
ни в values.

## Что в нём
| Ключ | Откуда |
|---|---|
| `DB_USER`, `DB_PASSWORD` | `terraform output -json secrets` |
| `KAFKA_PASSWORD`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`, `REDIS_PASSWORD` | Там же |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Там же |
| `JOURNAL_PRIVATE_KEY`, `JOURNAL_PUBLIC_KEY` | Создаётся один раз, хранится вечно: без него старые квитанции не проверить |
| `NEAR_ACCOUNT_ID`, `NEAR_PRIVATE_KEY` | Аккаунт нотариуса, `near generate-key` |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GIGACHAT_AUTH_KEY`, `YANDEX_API_KEY`, `YANDEX_FOLDER_ID` | Договоры с провайдерами |

## Создать
```
terraform -chdir=deploy/terraform/yandex output -json secrets > /tmp/s.json
kubectl -n costguard create secret generic costguard-secrets --from-env-file=<(jq -r 'to_entries[]|"\(.key)=\(.value)"' /tmp/s.json) \
  --from-literal=JOURNAL_PRIVATE_KEY=... --from-literal=JOURNAL_PUBLIC_KEY=... --dry-run=client -o yaml | kubectl apply -f -
shred -u /tmp/s.json
```
В продакшене секрет лучше вести из Lockbox через External Secrets Operator, тогда ручного шага нет.

## Сменить
Пароли баз меняются в Terraform (`terraform taint random_password.postgres && terraform apply`), затем секрет,
затем `kubectl rollout restart deploy -n costguard`. Ключ журнала не меняют: новые квитанции подписываются новым
ключом только вместе со сменой `signing_key_id`, а старый ключ остаётся для проверки.
