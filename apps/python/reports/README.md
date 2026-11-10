# costguard-reports

Spend forecasts and reports. Two jobs, both run by Airflow; neither is a long-running service.

## costguard-forecast

Forecasts the spend of a company and its departments and publishes it to the core, `PUT /api/v1/analytics/forecast`
([ADR 0034](../../../docs/adr/0034-forecasts-and-scenarios.md)). Weekly, `infra/airflow/dags/forecast_weekly.py`.

- spend by month from ClickHouse (`spend_by_month`, `spend_by_department_month`);
- every series is forecast by the champion of a backtest among the models of `models.py` (`backtest.py`,
  `forecast.py`); departments are reconciled with the company by MinT (`reconcile.py`);
- the registry baseline of each point (`baseline.py`) and the expected savings of open findings (`savings.py`);
- spend of each AI agent over the next days, with gradient boosting of small trees in plain Python among the candidates (`agents.py`,
  [ADR 0045](../../../docs/adr/0045-agent-spend-boosting.md));
- runs are logged to MLflow, and explanations come through the gateway (`tracking.py`, `explain.py`,
  [ADR 0044](../../../docs/adr/0044-report-explanations-forecast-tracking.md)).

```sh
CORE_URL=… CORE_TOKEN=… CLICKHOUSE_URL=http://clickhouse:8123 costguard-forecast --tenant <uuid> --months 24 --horizon 6
```

## costguard-report

The monthly XLSX or PDF report (`report.py`, fonts in `fonts/`). Monthly, `infra/airflow/dags/monthly_report.py`.

```sh
CORE_URL=… CORE_TOKEN=… costguard-report --format pdf --months 12 --out report.pdf [--tenant <uuid>]
```

With `CLICKHOUSE_URL` and `--tenant` the monthly spend comes from ClickHouse (invoices, payments, subscriptions and
agents); without them from the core, which knows agent spend only.

## Run

```sh
uv run costguard-forecast --help                               # from the repository root
docker build -f apps/python/reports/Dockerfile -t costguard-reports .
```

The models, the boosting among them, are plain Python (ADR 0034, 0045): the image needs no system libraries.

| Variable | |
|---|---|
| `CORE_URL`, `CORE_TOKEN` | the core and the token of the forecast or report service account |
| `CLICKHOUSE_URL`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD` | the cost registry; with row policies on, the reader user ([ADR 0035](../../../docs/adr/0035-ingress-keycloak-logs-row-policies.md)) |
| `CLICKHOUSE_TENANT_SETTING` | `1`: pass the tenant as a ClickHouse setting for the row policies |
| `MLFLOW_TRACKING_URI` | where forecast runs are logged; empty — not logged |
| `REPORTS_LLM_API_KEY`, `REPORTS_LLM_MODEL` | explanations of a report are written when both are set; the key is a virtual key of the gateway |
| `REPORTS_LLM_GATEWAY_URL`, `REPORTS_LLM_PROVIDER`, `REPORTS_LLM_FOLDER_ID` | |

## Tests

```sh
uv run pytest apps/python/reports
```
