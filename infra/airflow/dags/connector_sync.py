"""One pull of one connection, now: the «Синхронизировать» button of the «Подключённые системы» page (ADR 0019,
addendum «Ручной запуск через Airflow»).

Not scheduled. The core starts it through the Airflow REST API with
conf = {tenant_id, connection_id, connector}; the run id is `manual-<connection_id>-<request time>`, so a repeated
request starts nothing new. The task runs `costguard-connector-agent <tenant>.toml --once --only <connector>` with the
tenant's secrets prefix. The agent reads the connection from connectors.config.v1 as on every round and reports the
pull to connectors.runs.v1: that report, not this DAG's state, is what the page shows.
"""

from __future__ import annotations

import shlex
from datetime import datetime

from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DEFAULT_ARGS, env_prefix, tenants

# tenant_id -> its config file and secrets prefix, read when the DAG is parsed like the other DAGs
TENANTS = {str(c["tenant_id"]): {"path": c["path"], "prefix": env_prefix(name)}
           for name, c in tenants().items() if "tenant_id" in c}

CONF = "dag_run.conf"
TENANT = f"params.tenants[{CONF}['tenant_id']]"

with DAG(dag_id="costguard_connector_sync", schedule=None, start_date=datetime(2026, 1, 1), catchup=False,
         max_active_runs=4, default_args={**DEFAULT_ARGS, "retries": 0}, tags=["costguard", "connectors"],
         params={"tenants": TENANTS}, user_defined_filters={"sh": shlex.quote}) as dag:
    BashOperator(
        task_id="pull",
        bash_command=(f"costguard-connector-agent {{{{ {TENANT}.path | sh }}}} --once "
                      f"--only {{{{ {CONF}['connector'] | sh }}}}"),
        env={"COSTGUARD_ENV_PREFIX": f"{{{{ {TENANT}.prefix }}}}"},
        append_env=True,
    )
