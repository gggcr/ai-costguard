"""Pulls every tenant's connectors once an hour: 1C, МойСклад, Bitrix24, Яндекс 360, Jamf, Intune, Google
Workspace, Okta, Entra ID, Merge, Т-Банк and the invoice mailbox — whichever sections the tenant's config has.

Runs `costguard-connector-agent <tenant>.toml --once`; the agent keeps its cursors in the state file of the
config, so a failed run repeats only what was not published. Runs never overlap (max_active_runs=1).
"""

from __future__ import annotations

from datetime import datetime

from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DEFAULT_ARGS, env_prefix, tenants

with DAG(dag_id="costguard_connectors", schedule="@hourly", start_date=datetime(2026, 1, 1), catchup=False,
         max_active_runs=1, default_args=DEFAULT_ARGS, tags=["costguard", "connectors"]) as dag:
    for name, config in tenants().items():
        BashOperator(
            task_id=f"pull_{name}",
            bash_command=f"costguard-connector-agent {config['path']} --once",
            env={"COSTGUARD_ENV_PREFIX": env_prefix(name)},
            append_env=True,
        )
