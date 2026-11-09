"""Pulls the systems whose sources change once a day, at 05:00 Moscow time: Google Workspace (its usage reports lag
two days) and Merge (it syncs the linked systems about daily). The hourly DAG skips them.

One task per tenant: `costguard-connector-agent <tenant>.toml --once --only google_workspace,merge`, with the same
state file as the hourly DAG. A tenant without either system finishes at once: the agent has nothing to pull. The
sections may come from the tenant's file or from the core (connectors.config.v1), so every tenant gets a task.
"""

from __future__ import annotations

from datetime import datetime

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DAILY, DEFAULT_ARGS, env_prefix, tenants

with DAG(dag_id="costguard_connectors_daily", schedule="0 5 * * *",
         start_date=datetime(2026, 1, 1, tzinfo=pendulum.timezone("Europe/Moscow")), catchup=False,
         max_active_runs=1, default_args=DEFAULT_ARGS, tags=["costguard", "connectors"]) as dag:
    for name, config in tenants().items():
        BashOperator(
            task_id=f"pull_daily_{name}",
            bash_command=f"costguard-connector-agent {config['path']} --once --only {','.join(DAILY)}",
            env={"COSTGUARD_ENV_PREFIX": env_prefix(name)},
            append_env=True,
        )
