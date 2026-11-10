# costguard-connectors

Pulls vendors, people, licenses and costs from the systems a company already has and publishes them to
`ingest.normalized.v1` and `cost.records.v1`. The core merges them with what it has (`NormalizedEntities`).

| Connector | Source | Gives |
|---|---|---|
| `onec.OneC` | 1С OData (`/odata/standard.odata`) | vendors (Контрагенты), invoices and costs (ПоступлениеТоваровУслуг) |
| `moysklad.MoySklad` | МойСклад JSON API 1.2 | vendors, invoices and costs (приёмки) |
| `bitrix24.Bitrix24` | Bitrix24 incoming webhook | employees, seats, last login, dismissals |
| `yandex360.Yandex360` | Яндекс 360 directory and audit log | employees, seats, last login |
| `mail.MailInvoices` | IMAP mailbox | attachments uploaded to the core as imports: tables, PDFs, bank statements, and photos and scans for OCR (not logos: under `min_image_kb` 20 KB or a side under `min_image_side` 500 px) |
| `jamf.Jamf` | Jamf Pro API (OAuth client) | Macs, iPhones, iPads and their users |
| `intune.Intune` | Microsoft Graph, `deviceManagement/managedDevices` | company-owned laptops and phones and their users |
| `google_workspace.GoogleWorkspace` | Admin SDK Directory and Reports API (service account) | users, Google Workspace seats and last login, sign-ins to SAML apps |
| `okta.Okta` | Okta Users API and System Log | users, Okta seats and last login, sign-ins to apps (`user.authentication.sso`) |
| `entra.Entra` | Microsoft Graph: `subscribedSkus`, `users` with `signInActivity`, `auditLogs/signIns` | users, Microsoft licenses and their seats, sign-ins to apps |
| `merge.Merge` | Merge unified API, accounting and HRIS ([ADR 0014](../../../docs/adr/0014-unified-api-merge.md)) | suppliers, payable invoices and costs, payments, employees and dismissals |
| `banks.BankConnector` + `tbank.TBank` | Т-Банк T-Business API ([ADR 0015](../../../docs/adr/0015-bank-connector-intermediaries.md)) | outgoing transfers, card operations (МИР too), SBP payments and fees as payments |

Identity providers (`signins`) give a license per person and service with the last sign-in: the data of the
«paid but not used» finding. `apps` in the connector's section maps an app to a vendor and product, `ignore` drops
free apps. Bank payments through an intermediary count as the service in the purpose, and the agent's commission
separately when its amount is stated (`[payments]`, `costguard_common.payments`).

A device (`ASSET`) carries its serial and inventory numbers, model, type and holder. The core issues it to that
holder and makes a handover act; MDM systems send no cost, because only accounting knows it in the company's
currency.

Every connector implements `contract.Connector`: `pull(tenant, cursor) -> Batch`. It never publishes itself;
`contract.run` publishes and only then returns the new cursor, so a failed publish repeats the pull.

## Agent

`costguard-connector-agent config.toml` runs the connectors on a schedule; `--once` runs one round (cron,
Airflow). Inside the client's network it only connects out, to Kafka. The config format and the environment
variables for secrets are in `agent.py`.

Systems connected on the core's page come from `connectors.config.v1` and are merged over the file; a system
disconnected there is not pulled. Every pull is reported to `connectors.runs.v1`, with the secrets of the
environment cut out of errors. A running agent pulls a system at once when a person asks to sync it; with `--once`
it waits for the next round (`control.py`, ADR 0019).

## Not there yet

- The connectors were tested against recorded API shapes (`httpx.MockTransport`), not against live 1C,
  МойСклад, Bitrix24, Яндекс 360, Jamf or Intune accounts.
- Equipment from 1C (fixed assets) and HR from 1С:ЗУП, Workday or BambooHR have no connector.
- The agent publishes straight to Kafka; an HTTPS relay for networks where Kafka is not reachable is not written.
- Google Workspace, Okta, Entra ID, Merge and Т-Банк were tested on recorded API shapes only; the category names of
  Т-Банк operations need checking in the bank's sandbox.
- Other banks: implement `banks.Bank`; parsing, intermediaries and the cursor are shared.

## Image

```sh
docker build -f apps/python/connectors/Dockerfile -t costguard-connectors .   # from the repository root
```

The Helm CronJob `costguard-connectors` and the agent inside a client's network run `costguard-connector-agent` from it.
