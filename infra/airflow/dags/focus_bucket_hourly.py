"""Every hour at :20, reads the new FOCUS cost exports of every tenant whose config has a [focus_bucket] section from
its bucket and uploads them to the core (costguard-focus-pull, ADR 0046). AWS, Azure, Google Cloud and Yandex Cloud
write such exports once a day or more often; an hour is the most a new one waits.

[focus_bucket] takes bucket, and optionally endpoint (AWS when left out), region (ru-central1), prefix, days (7: older
objects are not read) and accept (true: FOCUS files that need nobody are confirmed by the job). The worker needs
<TENANT>_CORE_URL, <TENANT>_IMPORTER_TOKEN (a service account with the importer role), and
<TENANT>_FOCUS_BUCKET_ACCESS_KEY and _SECRET_KEY: a key that may only read the bucket. Which objects were read is kept
in $COSTGUARD_STATE_DIR/<tenant>/focus-bucket.json.
"""

from __future__ import annotations

import os
import shlex
from datetime import datetime

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

from costguard_common import DEFAULT_ARGS, env_prefix, tenants

STATE_DIR = os.environ.get("COSTGUARD_STATE_DIR", "/opt/costguard/state")

with DAG(dag_id="costguard_focus_bucket", schedule="20 * * * *",
         start_date=datetime(2026, 1, 1, tzinfo=pendulum.timezone("Europe/Moscow")), catchup=False,
         max_active_runs=1, default_args=DEFAULT_ARGS, tags=["costguard", "imports", "focus"]) as dag:
    for name, config in tenants().items():
        settings = config.get("focus_bucket")
        if not isinstance(settings, dict) or not settings.get("bucket"):
            continue
        prefix = env_prefix(name)
        args = ["--bucket", str(settings["bucket"]), "--prefix", str(settings.get("prefix", "")),
                "--region", str(settings.get("region", "ru-central1")), "--days", str(int(settings.get("days", 7))),
                "--state", f"{STATE_DIR}/{name}/focus-bucket.json"]
        if settings.get("endpoint"):
            args += ["--endpoint", str(settings["endpoint"])]
        if settings.get("accept", True):
            args.append("--accept")
        BashOperator(
            task_id=f"focus_bucket_{name}",
            bash_command=(f"CORE_URL=\"${prefix}CORE_URL\" CORE_TOKEN=\"${prefix}IMPORTER_TOKEN\" "
                          f"FOCUS_BUCKET_ACCESS_KEY=\"${prefix}FOCUS_BUCKET_ACCESS_KEY\" "
                          f"FOCUS_BUCKET_SECRET_KEY=\"${prefix}FOCUS_BUCKET_SECRET_KEY\" "
                          f"costguard-focus-pull {shlex.join(args)}"),
        )
