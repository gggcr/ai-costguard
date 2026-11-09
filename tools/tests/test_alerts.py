"""Alerts, their delivery and the backup jobs of the Helm chart (ADR 0025, ADR 0026).

Renders the chart with helm (on PATH or in $HELM); without it these tests are skipped. The deploy workflow has it.
"""

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "deploy/helm/costguard"
HELM = os.environ.get("HELM") or shutil.which("helm")

pytestmark = pytest.mark.skipif(HELM is None, reason="helm is not installed here")

ALERTING = ("alerting.enabled=true", "alerting.telegram.chatId=-1001234567890",
            "alerting.email.to=ops@costguard.example", "alerting.email.from=alerts@costguard.example",
            "alerting.email.smarthost=smtp.costguard.example:587")
BACKUPS = ("backups.enabled=true", "backups.bucket=costguard-prod-backups",
           "backups.filesBucket=costguard-prod-imports", "backups.ageRecipient=age1" + "q" * 58)


def render(*sets: str, values: str = "values-prod.yaml") -> list[dict]:
    args = [HELM, "template", "cg", str(CHART), "-f", str(CHART / "values.yaml"), "-f", str(CHART / values)]
    for s in sets:
        args += ["--set", s]
    out = subprocess.run(args, capture_output=True, text=True, check=True, encoding="utf-8").stdout
    return [d for d in yaml.safe_load_all(out) if d]


def rules(docs) -> dict[str, dict]:
    [rule] = [d for d in docs if d["kind"] == "PrometheusRule"]
    return {r["alert"]: r for g in rule["spec"]["groups"] for r in g["rules"]}


def test_every_alert_the_launch_needs_is_there_with_a_severity_and_a_runbook_that_exists():
    found = rules(render())
    # What part 9 asked for: the gateway, Kafka lag, the outbox, budget reconciliation, the notary, connectors,
    # disks; and the backups that part 9 adds.
    assert {"GatewayDown", "GatewaySlow", "GatewayErrors", "ConsumerLagGrowing", "OutboxStuck", "OutboxRetryFailing",
            "BudgetReconciliationMismatch", "BudgetReconciliationNotRunning", "NotaryNotAnchoring",
            "NotaryAnchorsMissing", "ConnectorFailing", "NodeDiskFilling", "VolumeFilling", "BackupFailed",
            "BackupMissing", "CoreDown"} <= set(found)
    for name, rule in found.items():
        assert rule["labels"]["severity"] in ("critical", "warning"), name
        runbook = rule["annotations"]["runbook"]
        assert (ROOT / runbook).is_file(), f"{name}: {runbook} does not exist"
        assert rule["annotations"]["runbook_url"].endswith(runbook.removeprefix("docs/runbooks")), name
        assert rule["annotations"]["summary"], name


def test_the_metrics_the_alerts_read_are_the_ones_the_core_exports():
    exported = {"costguard_outbox_oldest_pending_seconds", "costguard_outbox_retry_failures_total",
                "costguard_journal_receipts_issued_total", "costguard_journal_last_anchor_timestamp_seconds",
                "costguard_connector_runs_total", "costguard_budget_reconciliation_mismatches",
                "costguard_budget_reconciliation_last_success_timestamp_seconds"}
    expressions = " ".join(r["expr"] for r in rules(render()).values())
    used = set(re.findall(r"costguard_[a-z_]+", expressions))
    assert used == exported
    # The names the core registers (Micrometer turns dots into underscores, counters get _total).
    java = "\n".join(p.read_text("utf-8") for p in (ROOT / "apps/backend/core/src/main/java").rglob("*.java"))
    for metric in exported:
        base = metric.removesuffix("_total").replace("_", ".")
        assert f'"{base}"' in java, metric


def test_critical_alerts_go_to_telegram_and_email_and_warnings_to_telegram():
    [config] = [d for d in render(*ALERTING) if d["kind"] == "AlertmanagerConfig"]
    spec = config["spec"]
    assert spec["route"]["receiver"] == "telegram"
    [critical] = spec["route"]["routes"]
    assert critical["matchers"] == [{"name": "severity", "value": "critical", "matchType": "="}]
    receivers = {r["name"]: r for r in spec["receivers"]}
    assert set(receivers[critical["receiver"]]) >= {"telegramConfigs", "emailConfigs"}
    telegram = receivers["telegram"]["telegramConfigs"][0]
    assert telegram["chatID"] == -1001234567890
    assert telegram["botToken"] == {"name": "costguard-alerting", "key": "TELEGRAM_BOT_TOKEN"}
    assert "runbook_url" in telegram["message"]
    email = receivers["telegram-and-email"]["emailConfigs"][0]
    assert email["authPassword"] == {"name": "costguard-alerting", "key": "SMTP_PASSWORD"}
    # No secret value is in the chart: only references to the secret.
    assert "TELEGRAM_BOT_TOKEN" not in yaml.safe_dump(config).replace("key: TELEGRAM_BOT_TOKEN", "")


def test_alerting_is_off_by_default_and_refuses_to_render_half_configured():
    assert not [d for d in render() if d["kind"] == "AlertmanagerConfig"]
    with pytest.raises(subprocess.CalledProcessError):
        render("alerting.enabled=true")


def test_nightly_backups_run_as_the_read_only_role_and_encrypt_to_the_age_key():
    jobs = {d["metadata"]["name"]: d for d in render(*BACKUPS) if d["kind"] == "CronJob"}
    assert set(jobs) >= {"costguard-backup-postgres", "costguard-backup-clickhouse", "costguard-backup-files"}
    for name, job in jobs.items():
        if not name.startswith("costguard-backup-"):
            continue
        spec = job["spec"]
        assert spec["concurrencyPolicy"] == "Forbid"
        [container] = spec["jobTemplate"]["spec"]["template"]["spec"]["containers"]
        env = {e["name"]: e for e in container["env"]}
        assert env["BACKUP_TARGET"]["value"] == "s3://costguard-prod-backups"
        assert env["AGE_RECIPIENT"]["value"].startswith("age1")
        assert container["securityContext"]["readOnlyRootFilesystem"] is True
    pg = {e["name"]: e for e in jobs["costguard-backup-postgres"]["spec"]["jobTemplate"]["spec"]["template"]["spec"]
          ["containers"][0]["env"]}
    assert pg["PGUSER"]["valueFrom"]["secretKeyRef"]["key"] == "BACKUP_DB_USER"
    assert pg["PGPASSWORD"]["valueFrom"]["secretKeyRef"]["key"] == "BACKUP_DB_PASSWORD"
    files = {e["name"]: e for e in jobs["costguard-backup-files"]["spec"]["jobTemplate"]["spec"]["template"]["spec"]
             ["containers"][0]["env"]}
    assert files["BACKUP_FILES_SOURCE"]["value"] == "s3://costguard-prod-imports"


def test_backups_never_render_without_the_age_key():
    with pytest.raises(subprocess.CalledProcessError):
        render("backups.enabled=true", "backups.bucket=b", "backups.filesBucket=i")


def test_a_rotated_secret_restarts_the_services():
    for d in render():
        if d["kind"] == "Deployment" and d["metadata"]["name"] != "costguard-mlflow":
            assert d["metadata"]["annotations"]["secret.reloader.stakater.com/reload"] == "costguard-secrets"
