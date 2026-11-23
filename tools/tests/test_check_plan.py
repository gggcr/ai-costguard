"""tools/terraform/check_plan.py on small plans shaped like `terraform show -json` output."""

import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "check_plan", Path(__file__).resolve().parents[1] / "terraform" / "check_plan.py")
check_plan = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = check_plan
spec.loader.exec_module(check_plan)

TAGS = {"Project": "costguard", "Environment": "staging", "DataRegion": "us-east-2", "ManagedBy": "terraform"}


def resource(kind, after, actions=("create",), index=None, name="main"):
    return {"address": f"{kind}.{name}" + (f'["{index}"]' if index else ""), "mode": "managed", "type": kind,
            "name": name, "index": index, "change": {"actions": list(actions), "after": after, "after_unknown": {}}}


def plan(*resources, region="us-east-2"):
    return {"variables": {"region": {"value": region}}, "resource_changes": list(resources)}


def good():
    return [
        resource("aws_db_instance", {"publicly_accessible": False, "storage_encrypted": True, "tags_all": TAGS}),
        resource("aws_kms_key", {"enable_key_rotation": True, "tags_all": TAGS}),
        resource("aws_eks_cluster", {"vpc_config": [{"endpoint_public_access": True,
                                                     "public_access_cidrs": ["203.0.113.10/32"]}],
                                     "encryption_config": [{"resources": ["secrets"]}], "tags_all": TAGS}),
        resource("aws_s3_bucket", {"tags_all": TAGS}, index="imports"),
        resource("aws_s3_bucket_public_access_block", {"block_public_acls": True, "block_public_policy": True,
                                                       "ignore_public_acls": True, "restrict_public_buckets": True},
                 index="imports"),
        resource("aws_s3_bucket_server_side_encryption_configuration", {}, index="imports"),
        resource("aws_s3_bucket_policy", {}, index="imports"),
    ]


def test_a_good_plan_passes():
    problems, actions = check_plan.check(plan(*good()))
    assert problems == [] and actions["create"] == 7


def test_changes_to_existing_resources_fail_unless_allowed():
    replace = resource("aws_db_instance", {"publicly_accessible": False, "storage_encrypted": True,
                                           "tags_all": TAGS}, actions=("delete", "create"), name="old")
    assert any("changes or destroys" in p for p in check_plan.check(plan(*good(), replace))[0])
    assert check_plan.check(plan(*good(), replace), allow_changes=True)[0] == []


def test_a_region_outside_the_us_and_eu_fails():
    assert any("region" in p for p in check_plan.check(plan(*good(), region="ap-south-1"))[0])


def test_public_or_unencrypted_stores_fail():
    bad = [
        resource("aws_db_instance", {"publicly_accessible": True, "storage_encrypted": False, "tags_all": TAGS},
                 name="db"),
        resource("aws_vpc_security_group_ingress_rule", {"cidr_ipv4": "0.0.0.0/0", "tags_all": TAGS}),
        resource("aws_eks_cluster", {"vpc_config": [{"endpoint_public_access": True,
                                                     "public_access_cidrs": ["0.0.0.0/0"]}],
                                     "encryption_config": [], "tags_all": TAGS}, name="open"),
        resource("aws_kms_key", {"enable_key_rotation": False, "tags_all": TAGS}, name="norotate"),
    ]
    problems = check_plan.check(plan(*good(), *bad))[0]
    for expected in ("publicly accessible", "not encrypted", "admits the whole internet", "open to the whole",
                     "secrets are not encrypted", "rotation is off"):
        assert any(expected in p for p in problems), expected


def test_untagged_resources_and_unprotected_buckets_fail():
    untagged = resource("aws_kms_key", {"enable_key_rotation": True, "tags_all": {"Project": "costguard"}},
                        name="x")
    bucket = resource("aws_s3_bucket", {"tags_all": TAGS}, index="logs")
    problems = check_plan.check(plan(*good(), untagged, bucket))[0]
    assert any("tags missing" in p for p in problems)
    assert any("public_access_block missing" in p and "logs" in p for p in problems)
