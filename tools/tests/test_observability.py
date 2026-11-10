"""Logs go to Loki in the region of every other store and stay no longer than the bucket keeps them (ADR 0035).

The rendering test needs the helm binary on PATH or in $HELM; without it it is skipped.
"""

import re
from pathlib import Path

import pytest
import yaml
from test_helm import HELM, find, render
from test_residency import tf_default_region

ROOT = Path(__file__).resolve().parents[2]
ARGOCD = ROOT / "deploy/argocd"
DOCS = {d["metadata"]["name"]: d for d in yaml.safe_load_all((ARGOCD / "observability.yaml").read_text("utf-8")) if d}


def loki_values() -> dict:
    return DOCS["loki"]["spec"]["source"]["helm"]["valuesObject"]


def alloy_config() -> str:
    return DOCS["alloy"]["spec"]["source"]["helm"]["valuesObject"]["alloy"]["configMap"]["content"]


def test_loki_stores_in_the_region_of_every_store():
    s3 = loki_values()["loki"]["storage"]["s3"]
    chart = yaml.safe_load((ROOT / "deploy/helm/costguard/values.yaml").read_text("utf-8"))
    assert s3["region"] == tf_default_region() == chart["region"]
    assert "https://" + s3["endpoint"] == chart["regions"][chart["region"]]["s3Endpoint"]
    # keys come from a secret, never from the file
    assert s3["accessKeyId"] == "${S3_ACCESS_KEY}" and s3["secretAccessKey"] == "${S3_SECRET_KEY}"
    assert "-config.expand-env=true" in loki_values()["singleBinary"]["extraArgs"]


def test_loki_forgets_before_the_bucket_does():
    hours = int(loki_values()["loki"]["limits_config"]["retention_period"].removesuffix("h"))
    assert loki_values()["loki"]["compactor"]["retention_enabled"] is True
    variables = (ROOT / "deploy/terraform/yandex/variables.tf").read_text("utf-8")
    bucket_days = int(re.search(r'variable "log_retention_days" \{[^}]*default\s*=\s*(\d+)', variables).group(1))
    assert hours / 24 <= bucket_days
    main = (ROOT / "deploy/terraform/yandex/main.tf").read_text("utf-8")
    assert 'resource "yandex_storage_bucket" "logs"' in main and "days = var.log_retention_days" in main


def test_alloy_reads_only_costguard_namespaces_and_pods():
    namespaces = {yaml.safe_load((ARGOCD / f"{env}.yaml").read_text("utf-8"))["spec"]["destination"]["namespace"]
                  for env in ("staging", "prod")}
    config = alloy_config()
    listed = set(re.search(r"names = \[([^\]]*)\]", config).group(1).replace('"', "").replace(" ", "").split(","))
    assert listed == namespaces
    assert 'regex         = "costguard"' in config and 'action        = "keep"' in config
    assert "loki-gateway.observability.svc.cluster.local/loki/api/v1/push" in config


@pytest.mark.parametrize("line,level", [
    ("2026-11-10T17:54:31.754+03:00 ERROR 24816 --- [core] [main] a.c.Core : boom", "ERROR"),
    ("2026-11-10 17:54:31,754 WARNING costguard_ingest.service skipped", "WARNING"),
    ("2026-11-10T17:54:31.754+03:00  INFO 1 --- [gateway] started", "INFO"),
])
def test_the_level_of_spring_and_python_lines_becomes_a_label(line, level):
    expression = re.search(r'expression = "(.*)"', alloy_config()).group(1).encode().decode("unicode_escape")
    assert re.match(expression, line).group("level") == level


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_the_chart_adds_loki_to_grafana_only_when_asked():
    assert find(render(), "ConfigMap", "costguard-loki-datasource") == []
    [cm] = find(render(sets=("logging.enabled=true",)), "ConfigMap", "costguard-loki-datasource")
    assert cm["metadata"]["labels"]["grafana_datasource"] == "1"
    [source] = yaml.safe_load(cm["data"]["costguard-loki.yaml"])["datasources"]
    assert source["type"] == "loki" and source["url"] == "http://loki-gateway.observability.svc.cluster.local"


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_the_migration_job_applies_row_policies_only_when_asked():
    def job_env(*sets):
        [job] = [d for d in render(sets=sets) if d["kind"] == "Job"]
        return {e["name"]: e.get("value") for e in job["spec"]["template"]["spec"]["containers"][0]["env"]}

    assert "CLICKHOUSE_ACCESS_MANAGEMENT" not in job_env()
    assert job_env("clickhouseMigrations.rowPolicies=true")["CLICKHOUSE_ACCESS_MANAGEMENT"] == "1"
