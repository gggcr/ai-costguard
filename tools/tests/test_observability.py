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


# --- Prometheus, Grafana and traces (ADR 0048) ---------------------------------------------------------------------

COLLECTOR = ROOT / "infra/observability/otel-collector.yaml"


def test_the_cluster_runs_the_collector_config_of_infra_observability():
    values = DOCS["otel-collector"]["spec"]["source"]["helm"]["valuesObject"]
    assert values["alternateConfig"] == yaml.safe_load(COLLECTOR.read_text("utf-8"))
    tempo = {e["name"]: e["value"] for e in values["extraEnvs"]}["TEMPO_OTLP_ENDPOINT"]
    assert tempo.startswith("tempo.observability.svc.cluster.local:")


def test_the_collector_drops_personal_data_before_tempo():
    config = yaml.safe_load(COLLECTOR.read_text("utf-8"))
    dropped = {a["key"] for a in config["processors"]["attributes/personal-data"]["actions"] if a["action"] == "delete"}
    assert {"enduser.id", "user.email", "http.request.header.authorization", "url.query"} <= dropped
    [pipeline] = config["service"]["pipelines"].values()
    assert "attributes/personal-data" in pipeline["processors"]
    assert pipeline["processors"].index("attributes/personal-data") < pipeline["processors"].index("batch")


def test_prometheus_and_alertmanager_pick_up_what_the_chart_makes():
    stack = DOCS["kube-prometheus-stack"]["spec"]["source"]["helm"]["valuesObject"]
    spec = stack["prometheus"]["prometheusSpec"]
    assert spec["serviceMonitorSelectorNilUsesHelmValues"] is False
    assert spec["ruleSelectorNilUsesHelmValues"] is False
    chart = yaml.safe_load((ROOT / "deploy/helm/costguard/values.yaml").read_text("utf-8"))
    selector = stack["alertmanager"]["alertmanagerSpec"]["alertmanagerConfigSelector"]["matchLabels"]
    assert selector == chart["alerting"]["selectorLabels"]
    sidecar = stack["grafana"]["sidecar"]
    assert sidecar["dashboards"]["label"] == "grafana_dashboard"
    assert sidecar["datasources"]["label"] == "grafana_datasource"
    # the Tempo data source links to the logs by the uid the chart gives Loki
    [tempo] = stack["grafana"]["additionalDataSources"]
    assert tempo["jsonData"]["tracesToLogsV2"]["datasourceUid"] == "costguard-loki"
    assert "costguard-loki" in (ROOT / "deploy/helm/costguard/templates/logging.yaml").read_text("utf-8")


def test_the_observability_project_allows_every_chart_it_installs():
    project = DOCS["observability"]["spec"]
    apps = [d for d in DOCS.values() if d["kind"] == "Application"]
    assert {a["spec"]["source"]["repoURL"] for a in apps} <= set(project["sourceRepos"])
    assert all(a["spec"]["project"] == "observability" for a in apps)


def test_the_chart_points_traces_at_the_collector():
    chart = yaml.safe_load((ROOT / "deploy/helm/costguard/values.yaml").read_text("utf-8"))
    assert chart["tracing"]["otlpEndpoint"].startswith("http://otel-collector.observability.svc.cluster.local:4318")
    assert DOCS["otel-collector"]["spec"]["source"]["helm"]["valuesObject"]["fullnameOverride"] == "otel-collector"


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_the_java_services_export_traces_only_when_asked():
    from test_helm import env_of

    def env(*sets):
        [core] = find(render(sets=sets), "Deployment", "costguard-core")
        return {k: v.get("value") for k, v in env_of(core).items()}

    off = env()
    assert off["MANAGEMENT_TRACING_EXPORT_ENABLED"] == "false"
    assert "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT" not in off
    on = env("tracing.enabled=true")
    assert on["MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT"].endswith(":4318/v1/traces")
    assert "MANAGEMENT_TRACING_EXPORT_ENABLED" not in on
    # the Python services read the standard OpenTelemetry variable and a switch of their own (ADR 0050)
    assert on["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] == on["MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT"]
    assert on["COSTGUARD_TRACING"] == "true"
    assert "COSTGUARD_TRACING" not in off


def test_the_local_stack_uses_the_same_config_and_dashboard():
    compose = yaml.safe_load((ROOT / "infra/local/docker-compose.yml").read_text("utf-8"))["services"]
    assert "../observability/otel-collector.yaml:/etc/otelcol/config.yaml:ro" in compose["otel-collector"]["volumes"]
    assert any(v.startswith("../../deploy/helm/costguard/dashboards:") for v in compose["grafana"]["volumes"])
    for name in ("otel-collector", "tempo", "prometheus", "grafana"):
        assert compose[name]["profiles"] == ["observability"]
    # traces are off unless asked: an exporter without a collector would only log errors
    assert compose["core"]["environment"]["MANAGEMENT_TRACING_EXPORT_ENABLED"] == "${TRACING:-false}"
