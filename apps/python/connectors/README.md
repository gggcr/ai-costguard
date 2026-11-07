# costguard-connectors

Pulls vendors, people, licenses and costs from the systems a company already has and publishes them to
`ingest.normalized.v1` and `cost.records.v1`. The core merges them with what it has (`NormalizedEntities`).

| Connector | Source | Gives |
|---|---|---|
| `onec.OneC` | 1С OData (`/odata/standard.odata`) | vendors (Контрагенты), invoices and costs (ПоступлениеТоваровУслуг) |
| `moysklad.MoySklad` | МойСклад JSON API 1.2 | vendors, invoices and costs (приёмки) |
| `bitrix24.Bitrix24` | Bitrix24 incoming webhook | employees, seats, last login |
| `yandex360.Yandex360` | Яндекс 360 directory and audit log | employees, seats, last login |
| `mail.MailInvoices` | IMAP mailbox | attachments uploaded to the core as imports |

Every connector implements `contract.Connector`: `pull(tenant, cursor) -> Batch`. It never publishes itself;
`contract.run` publishes and only then returns the new cursor, so a failed publish repeats the pull.

## Agent

`costguard-connector-agent config.toml` runs the connectors on a schedule; `--once` runs one round (cron,
Airflow). Inside the client's network it only connects out, to Kafka. The config format and the environment
variables for secrets are in `agent.py`.

## Not there yet

- The connectors were tested against recorded API shapes (`httpx.MockTransport`), not against live 1C,
  МойСклад, Bitrix24 or Яндекс 360 accounts.
- The agent publishes straight to Kafka; an HTTPS relay for networks where Kafka is not reachable is not written.
- Unified APIs (Merge, Finch and the like) have no connector.
