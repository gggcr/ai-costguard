# Airflow schedules

| DAG | When | What |
|---|---|---|
| `costguard_connectors` | every hour | `costguard-connector-agent <tenant>.toml --once` per tenant: every connector of its config, including Google Workspace, Okta, Entra ID, Merge and Т-Банк |
| `costguard_connectors_daily` | 05:00 Moscow | `--once --only google_workspace,merge` per tenant: Google's usage reports and Merge's syncs change once a day; the hourly DAG skips them |
| `costguard_connector_sync` | not scheduled: started by the core | one pull of one system now, the «Синхронизировать» button: `--once --only <type>` for the tenant of `conf.tenant_id` ([ADR 0019](../../docs/adr/0019-connected-systems.md)) |
| `costguard_focus_bucket` | every hour at :20 | `costguard-focus-pull`: new FOCUS cost exports from the bucket of a tenant with a `[focus_bucket]` section go to «Импорт», and the ones that need nobody are confirmed ([ADR 0046](../../docs/adr/0046-focus-from-bucket.md)) |
| `costguard_monthly_report` | 1st of the month, 06:00 Moscow | XLSX and PDF report of last month per tenant with a `[report]` section |

A tenant is one file in `$COSTGUARD_TENANTS_DIR` (`infra/local/airflow/tenants` locally), in the format of
`costguard_connectors.agent`, plus an optional `[report]` with `months`. Secrets come from the environment,
prefixed with the file name in upper case (Cyrillic transliterated): `ROMASHKA_ONEC_PASSWORD`,
`ROMASHKA_CORE_URL`, `ROMASHKA_CORE_TOKEN`, `ROMASHKA_OKTA_TOKEN`, `ROMASHKA_TBANK_TOKEN`. The sections the agent
reads are `costguard_connectors.agent.SECTIONS`; `tests/test_dags.py` checks a config against them.

`make airflow` starts Airflow 2.10 in standalone mode on http://localhost:8088.

`tests/test_dags.py` loads the DAGs against a stand-in for Airflow, because Airflow does not install on the
Python 3.14 of the workspace. The DAGs have not been parsed by a real Airflow yet.

Audit, anomaly detection and the notary are not here: the audit engine runs inside the core every hour, and the
anomaly detector and the notary are long-running Kafka consumers.
