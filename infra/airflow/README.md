# Airflow schedules

| DAG | When | What |
|---|---|---|
| `costguard_connectors` | every hour | `costguard-connector-agent <tenant>.toml --once` per tenant |
| `costguard_monthly_report` | 1st of the month, 06:00 Moscow | XLSX and PDF report of last month per tenant with a `[report]` section |

A tenant is one file in `$COSTGUARD_TENANTS_DIR` (`infra/local/airflow/tenants` locally), in the format of
`costguard_connectors.agent`, plus an optional `[report]` with `months`. Secrets come from the environment,
prefixed with the file name in upper case (Cyrillic transliterated): `ROMASHKA_ONEC_PASSWORD`,
`ROMASHKA_CORE_URL`, `ROMASHKA_CORE_TOKEN`.

`make airflow` starts Airflow 2.10 in standalone mode on http://localhost:8088.

`tests/test_dags.py` loads the DAGs against a stand-in for Airflow, because Airflow does not install on the
Python 3.14 of the workspace. The DAGs have not been parsed by a real Airflow yet.

Audit, anomaly detection and the notary are not here: the audit engine runs inside the core every hour, and the
anomaly detector and the notary are long-running Kafka consumers.
