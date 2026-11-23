"""Every store lives in one region, set in one place per tool (ADR 0024).

Terraform places the managed services; `region` decides every zone. The Helm chart points the services at them;
`region` decides the S3 endpoint and the signing region. The two must name the same region.
"""

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "deploy/terraform/yandex"
CHART = ROOT / "deploy/helm/costguard"
HELM = os.environ.get("HELM") or shutil.which("helm")

# A region or a zone spelled out: ru-central1, ru-central1-a.
LITERAL = re.compile(r'"[a-z]{2}-[a-z]+[0-9](-[a-z])?"')


def tf_default_region() -> str:
    block = re.search(r'variable "region" \{(.*?)\n\}', (TF / "variables.tf").read_text("utf-8"), re.S).group(1)
    return re.search(r'default\s*=\s*"([^"]+)"', block).group(1)


def test_terraform_places_every_store_in_zones_of_its_one_region():
    main = (TF / "main.tf").read_text("utf-8")
    code = "\n".join(line.split("#")[0] for line in main.splitlines())
    # The only literal region is the key of the endpoint map; zones and regions everywhere else are derived.
    literals = [m.group(0) for m in LITERAL.finditer(code)]
    assert literals == ['"ru-central1"'], literals
    assert re.search(r"storage_endpoints = \{\s*\"ru-central1\"", code)
    assert "zones = [for s in var.zone_suffixes : \"${var.region}-${s}\"]" in code
    for line in code.splitlines():
        stripped = line.strip()
        if re.match(r"(zone|zones|region)\s*=", stripped):
            assert re.search(r"(local\.zones|var\.region|each\.|location\.|host\.)", stripped), stripped
    assert "var.zones" not in code
    # Every managed store with data is in the module, so all of them are covered by the rule above.
    for store in ("yandex_mdb_postgresql_cluster", "yandex_mdb_kafka_cluster", "yandex_mdb_clickhouse_cluster",
                  "yandex_mdb_redis_cluster", "yandex_storage_bucket", "yandex_kubernetes_node_group"):
        assert f'resource "{store}"' in code, store
    variables = (TF / "variables.tf").read_text("utf-8")
    assert 'variable "zones"' not in variables


def test_helm_and_terraform_name_the_same_region():
    values = yaml.safe_load((CHART / "values.yaml").read_text("utf-8"))
    assert values["region"] == tf_default_region()
    assert values["region"] in values["regions"]
    assert values["external"]["s3Endpoint"] == ""
    for env in ("values-staging.yaml", "values-prod.yaml"):
        override = yaml.safe_load((CHART / env).read_text("utf-8")) or {}
        assert "region" not in override and "s3Endpoint" not in (override.get("external") or {}), env


def test_the_state_bucket_says_the_same_region():
    backend = (TF / "backend.tf.example").read_text("utf-8")
    assert f'region                      = "{tf_default_region()}"' in backend


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_the_rendered_chart_points_every_service_at_its_region():
    def render(*sets: str) -> list[dict]:
        args = [HELM, "template", "cg", str(CHART), "-f", str(CHART / "values.yaml"), "-f",
                str(CHART / "values-prod.yaml")]
        for s in sets:
            args += ["--set", s]
        out = subprocess.run(args, capture_output=True, text=True, check=True, encoding="utf-8").stdout
        return [d for d in yaml.safe_load_all(out) if d]

    for doc in render():
        if doc["kind"] != "Deployment":
            continue
        for container in doc["spec"]["template"]["spec"]["containers"]:
            env = {e["name"]: e.get("value") for e in container.get("env", [])}
            if "S3_ENDPOINT" in env:
                assert env["S3_ENDPOINT"] == "https://storage.yandexcloud.net"
                assert env["S3_REGION"] == env["DATA_REGION"] == "ru-central1"
            if "MLFLOW_S3_ENDPOINT_URL" in env:
                assert env["AWS_DEFAULT_REGION"] == "ru-central1"
    # a region of the AWS module (ADR 0078) gets its own S3 endpoint
    for doc in render("region=eu-central-1"):
        if doc["kind"] == "Deployment":
            for container in doc["spec"]["template"]["spec"]["containers"]:
                env = {e["name"]: e.get("value") for e in container.get("env", [])}
                if "S3_ENDPOINT" in env:
                    assert env["S3_ENDPOINT"] == "https://s3.eu-central-1.amazonaws.com"
                    assert env["S3_REGION"] == env["DATA_REGION"] == "eu-central-1"
    with pytest.raises(subprocess.CalledProcessError):
        render("region=ap-south-1")
