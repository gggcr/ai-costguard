"""The Helm chart renders KEDA and MLflow only where the values switch them on (ADR 0017).

Needs the helm binary on PATH or in $HELM; the deploy workflow installs it. Without it the tests are skipped.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "deploy/helm/costguard"
HELM = os.environ.get("HELM") or shutil.which("helm")

pytestmark = pytest.mark.skipif(HELM is None, reason="helm is not installed here")


def render(*values: str, sets: tuple[str, ...] = ()) -> list[dict]:
    args = [HELM, "template", "cg", str(CHART)]
    for v in values:
        args += ["-f", str(CHART / v)]
    for s in sets:
        args += ["--set", s]
    out = subprocess.run(args, capture_output=True, text=True, check=True, encoding="utf-8").stdout
    return [d for d in yaml.safe_load_all(out) if d]


def find(docs, kind, name=None):
    return [d for d in docs if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)]


def env_of(deployment) -> dict:
    return {e["name"]: e for e in deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])}


def test_off_by_default():
    docs = render()
    assert find(docs, "ScaledObject") == [] and find(docs, "Deployment", "costguard-mlflow") == []
    [ingest] = find(docs, "Deployment", "costguard-ingest")
    assert ingest["spec"]["replicas"] == 2
    assert "MLFLOW_TRACKING_URI" not in env_of(ingest)


@pytest.mark.parametrize("values,max_replicas,bucket", [("values-staging.yaml", 3, "costguard-staging-mlflow"),
                                                        ("values-prod.yaml", 6, "costguard-prod-mlflow")])
def test_staging_and_prod_scale_ingest_by_lag_and_run_mlflow(values, max_replicas, bucket):
    docs = render("values.yaml", values)
    [scaled] = find(docs, "ScaledObject")
    assert scaled["spec"]["scaleTargetRef"]["name"] == "costguard-ingest"
    assert scaled["spec"]["maxReplicaCount"] == max_replicas
    triggers = scaled["spec"]["triggers"]
    assert [t["metadata"]["topic"] for t in triggers] == ["ingest.files.v1", "ingest.accepted.v1"]
    assert {t["metadata"]["consumerGroup"] for t in triggers} == {"ingest-pipeline"}
    assert all("authenticationRef" not in t for t in triggers) and find(docs, "TriggerAuthentication") == []

    [ingest] = find(docs, "Deployment", "costguard-ingest")
    assert "replicas" not in ingest["spec"]  # KEDA sets it
    for single in ("costguard-anomaly", "costguard-notary"):
        [d] = find(docs, "Deployment", single)
        assert d["spec"]["replicas"] == 1

    [mlflow] = find(docs, "Deployment", "costguard-mlflow")
    args = mlflow["spec"]["template"]["spec"]["containers"][0]["args"]
    assert f"s3://{bucket}" in args and "--serve-artifacts" in args
    secrets = {k: v["valueFrom"]["secretKeyRef"]["key"] for k, v in env_of(mlflow).items() if "valueFrom" in v}
    assert secrets == {"MLFLOW_BACKEND_STORE_URI": "MLFLOW_BACKEND_STORE_URI", "AWS_ACCESS_KEY_ID": "S3_ACCESS_KEY",
                       "AWS_SECRET_ACCESS_KEY": "S3_SECRET_KEY"}
    assert find(docs, "Service", "costguard-mlflow")[0]["spec"]["ports"][0]["port"] == 80
    [anomaly] = find(docs, "Deployment", "costguard-anomaly")
    assert env_of(anomaly)["MLFLOW_TRACKING_URI"]["value"] == "http://costguard-mlflow"


def test_kafka_authentication_comes_from_the_secret():
    docs = render("values.yaml", "values-prod.yaml", sets=("keda.kafkaAuth=true",))
    [auth] = find(docs, "TriggerAuthentication")
    keys = {r["parameter"]: r["key"] for r in auth["spec"]["secretTargetRef"]}
    assert keys == {"sasl": "KEDA_KAFKA_SASL", "username": "KEDA_KAFKA_USERNAME", "password": "KAFKA_PASSWORD",
                    "tls": "KEDA_KAFKA_TLS"}
    triggers = find(docs, "ScaledObject")[0]["spec"]["triggers"]
    assert all(t["authenticationRef"]["name"] == "costguard-kafka" for t in triggers)
