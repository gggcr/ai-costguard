"""On the 1st at 06:00 Moscow time, writes last month's spend report with a forecast, as XLSX and PDF, for every
tenant whose config has a [report] section. Files go to $COSTGUARD_REPORTS_DIR/<tenant>/<month>.xlsx|pdf.
With REPORTS_LLM_API_KEY and REPORTS_LLM_MODEL (a virtual key of the platform's agent, ADR 0013) a language model
writes the explanation of the report through the gateway; without them, or when its numbers are off, a template does
(ADR 0044).
"""

from __future__ import annotations

from datetime import datetime

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DEFAULT_ARGS, REPORTS_DIR, env_prefix, tenants

with DAG(dag_id="costguard_monthly_report", schedule="0 6 1 * *",
         start_date=datetime(2026, 1, 1, tzinfo=pendulum.timezone("Europe/Moscow")), catchup=False,
         default_args=DEFAULT_ARGS, tags=["costguard", "reports"]) as dag:
    for name, config in tenants().items():
        report = config.get("report")
        if report is None:
            continue
        months = int(report.get("months", 12))
        prefix = env_prefix(name)
        for fmt in ("xlsx", "pdf"):
            out = f"{REPORTS_DIR}/{name}/{{{{ data_interval_start.strftime('%Y-%m') }}}}.{fmt}"
            BashOperator(
                task_id=f"report_{name}_{fmt}",
                bash_command=(f"mkdir -p {REPORTS_DIR}/{name} && CORE_URL=\"${prefix}CORE_URL\" "
                              f"CORE_TOKEN=\"${prefix}CORE_TOKEN\" costguard-report --format {fmt} "
                              f"--months {months} --out {out}"),
            )
