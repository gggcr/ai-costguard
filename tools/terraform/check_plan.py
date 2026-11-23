"""Checks a Terraform plan of deploy/terraform/aws before anyone applies it.

    terraform plan -var-file=staging.tfvars -out=plan.out
    terraform show -json plan.out > plan.json
    python tools/terraform/check_plan.py plan.json [--allow-changes]

Fails (exit 1) and says why when the plan would: change or destroy existing resources (unless --allow-changes),
put anything in a region outside the US/EU list, leave a resource without the Project/Environment/DataRegion tags,
make a database, cache, bucket or cluster reachable from the internet, store data unencrypted, or open a security
group to the whole internet. These are the promises of ADR 0078 and docs/AWS_DEPLOYMENT_GUIDE.md.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

REGIONS = {"us-east-1", "us-east-2", "us-west-2", "eu-central-1", "eu-west-1", "eu-north-1"}
TAGS = ("Project", "Environment", "DataRegion", "ManagedBy")
# resource types that take tags (the AWS provider applies default_tags to them as tags_all)
UNTAGGED = {
    "aws_route_table_association", "aws_iam_role_policy", "aws_iam_role_policy_attachment", "aws_kms_alias",
    "aws_s3_bucket_public_access_block", "aws_s3_bucket_ownership_controls",
    "aws_s3_bucket_server_side_encryption_configuration", "aws_s3_bucket_versioning",
    "aws_s3_bucket_lifecycle_configuration", "aws_s3_bucket_policy", "aws_ecr_lifecycle_policy",
    "aws_secretsmanager_secret_version", "aws_msk_scram_secret_association", "aws_msk_configuration",
    "aws_default_security_group", "random_password", "aws_eks_addon_version",
}


def changes(plan: dict) -> list[dict]:
    return [c for c in plan.get("resource_changes", []) if c.get("mode") == "managed"]


def after(c: dict) -> dict:
    return (c.get("change") or {}).get("after") or {}


def check(plan: dict, allow_changes: bool = False) -> tuple[list[str], Counter]:
    problems: list[str] = []
    actions: Counter = Counter()
    region = (((plan.get("configuration") or {}).get("provider_config") or {}).get("aws") or {}) \
        .get("expressions", {}).get("region", {}).get("constant_value")
    region = region or (plan.get("variables") or {}).get("region", {}).get("value")
    if region not in REGIONS:
        problems.append(f"region {region!r} is not one of the US/EU regions {sorted(REGIONS)}")

    for c in changes(plan):
        address, kind, act = c["address"], c["type"], tuple(c["change"]["actions"])
        actions["/".join(act)] += 1
        if act not in (("create",), ("no-op",), ("read",)) and not allow_changes:
            problems.append(f"{address}: {'/'.join(act)} — the plan changes or destroys something that exists")
        a = after(c)
        if act == ("delete",):
            continue
        unknown = (c.get("change") or {}).get("after_unknown") or {}

        if kind.startswith("aws_") and kind not in UNTAGGED and "tags_all" in a | unknown:
            tags = a.get("tags_all") or {}
            missing = [t for t in TAGS if t not in tags]
            if missing and not unknown.get("tags_all"):
                problems.append(f"{address}: tags missing {missing}")

        if kind == "aws_db_instance":
            if a.get("publicly_accessible"):
                problems.append(f"{address}: the database is publicly accessible")
            if not a.get("storage_encrypted"):
                problems.append(f"{address}: database storage is not encrypted")
        if kind == "aws_elasticache_replication_group":
            if not (a.get("at_rest_encryption_enabled") in (True, "true") and a.get("transit_encryption_enabled")):
                problems.append(f"{address}: Redis without encryption at rest and in transit")
        if kind == "aws_msk_cluster":
            transit = ((a.get("encryption_info") or [{}])[0].get("encryption_in_transit") or [{}])[0]
            if transit.get("client_broker") != "TLS":
                problems.append(f"{address}: Kafka clients may connect without TLS")
        if kind == "aws_s3_bucket_public_access_block":
            if not all(a.get(k) for k in ("block_public_acls", "block_public_policy", "ignore_public_acls",
                                          "restrict_public_buckets")):
                problems.append(f"{address}: public access is not fully blocked")
        if kind == "aws_eks_cluster":
            vpc = (a.get("vpc_config") or [{}])[0]
            if vpc.get("endpoint_public_access") and "0.0.0.0/0" in (vpc.get("public_access_cidrs") or []):
                problems.append(f"{address}: the Kubernetes API is open to the whole internet")
            if not a.get("encryption_config"):
                problems.append(f"{address}: Kubernetes secrets are not encrypted with KMS")
        if kind in ("aws_vpc_security_group_ingress_rule",) and a.get("cidr_ipv4") == "0.0.0.0/0":
            problems.append(f"{address}: a security group admits the whole internet")
        if kind == "aws_security_group":
            for rule in a.get("ingress") or []:
                if "0.0.0.0/0" in (rule.get("cidr_blocks") or []):
                    problems.append(f"{address}: a security group admits the whole internet")
        if kind == "aws_kms_key" and not a.get("enable_key_rotation"):
            problems.append(f"{address}: KMS key rotation is off")

    buckets = {c["index"] for c in changes(plan) if c["type"] == "aws_s3_bucket"}
    for name in ("aws_s3_bucket_public_access_block", "aws_s3_bucket_server_side_encryption_configuration",
                 "aws_s3_bucket_policy"):
        covered = {c["index"] for c in changes(plan) if c["type"] == name}
        if buckets - covered:
            problems.append(f"{name} missing for buckets {sorted(buckets - covered)}")
    return problems, actions


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan", type=Path, help="output of terraform show -json <planfile>")
    parser.add_argument("--allow-changes", action="store_true", help="accept updates and replacements")
    args = parser.parse_args(argv)
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    problems, actions = check(plan, args.allow_changes)
    print("actions: " + ", ".join(f"{k} {v}" for k, v in sorted(actions.items())))
    for p in problems:
        print("FAIL  " + p)
    print("plan check: " + ("passed" if not problems else f"{len(problems)} problem(s)"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
