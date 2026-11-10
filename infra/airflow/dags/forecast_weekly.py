"""On Mondays at 07:00 Moscow time, forecasts the spend of every tenant whose config has a [forecast] section and
publishes it to the core (costguard-forecast, ADR 0034). The models are fitted anew on every run: the weekly run
is the retraining.

[forecast] takes months (history, 24) and horizon (6). The worker needs <TENANT>_CORE_URL, <TENANT>_CORE_TOKEN (the
forecast service account) and CLICKHOUSE_URL.
"""

from __future__ import annotations

from datetime import datetime

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DEFAULT_ARGS, env_prefix, tenants

with DAG(dag_id="costguard_forecast_weekly", schedule="0 7 * * 1",
         start_date=datetime(2026, 1, 1, tzinfo=pendulum.timezone("Europe/Moscow")), catchup=False,
         max_active_runs=1, default_args=DEFAULT_ARGS, tags=["costguard", "forecast"]) as dag:
    for name, config in tenants().items():
        settings = config.get("forecast")
        if settings is None or "tenant_id" not in config:
            continue
        prefix = env_prefix(name)
        BashOperator(
            task_id=f"forecast_{name}",
            bash_command=(f"CORE_URL=\"${prefix}CORE_URL\" CORE_TOKEN=\"${prefix}CORE_TOKEN\" costguard-forecast "
                          f"--tenant {config['tenant_id']} --months {int(settings.get('months', 24))} "
                          f"--horizon {int(settings.get('horizon', 6))}"),
        )
