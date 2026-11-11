"""The core writes uploads to the bucket Terraform made, and the ingest pipeline reads them from the same one
(ADR 0049). The rendering test needs the helm binary on PATH or in $HELM; without it it is skipped."""

import re
from pathlib import Path

import pytest
import yaml
from test_helm import HELM, env_of, find, render

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "deploy/helm/costguard"


@pytest.mark.parametrize("env", ["staging", "prod"])
def test_each_environment_names_the_bucket_terraform_makes(env):
    values = yaml.safe_load((CHART / f"values-{env}.yaml").read_text("utf-8"))
    main = (ROOT / "deploy/terraform/yandex/main.tf").read_text("utf-8")
    assert re.search(r'name\s*=\s*"costguard-\$\{var\.environment\}"', main)
    assert re.search(r'resource "yandex_storage_bucket" "imports" \{\s*bucket\s*=\s*"\$\{local\.name\}-imports"', main)
    assert values["imports"]["bucket"] == f"costguard-{env}-imports"
    # the nightly copy saves the bucket the files are in
    assert values["backups"]["filesBucket"] == values["imports"]["bucket"]


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_the_core_and_ingest_use_one_bucket():
    docs = render("values-prod.yaml")
    [core] = find(docs, "Deployment", "costguard-core")
    [ingest] = find(docs, "Deployment", "costguard-ingest")
    assert env_of(core)["COSTGUARD_IMPORTS_S3_BUCKET"]["value"] == "costguard-prod-imports"
    assert env_of(ingest)["INGEST_S3_BUCKET"]["value"] == "costguard-prod-imports"
    # both reach S3 in the region of every store
    assert env_of(core)["S3_ENDPOINT"]["value"] == env_of(ingest)["S3_ENDPOINT"]["value"]


@pytest.mark.skipif(HELM is None, reason="helm is not installed here")
def test_a_release_without_a_bucket_does_not_render():
    import subprocess

    with pytest.raises(subprocess.CalledProcessError) as e:
        render(sets=("imports.bucket=",))
    assert "imports.bucket is required" in e.value.stderr
