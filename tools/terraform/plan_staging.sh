#!/usr/bin/env bash
# terraform plan of the AWS staging installation, and its checks. Creates nothing in AWS and costs nothing: the plan
# only reads the account id and the availability zones (deploy/terraform/aws/plan-policy.json is all it needs).
#
#   AWS_PROFILE=costguard-plan tools/terraform/plan_staging.sh [out-dir]
#
# Credentials come from the AWS profile (or the usual AWS_* variables); this script never reads or prints them.
# Output: <out-dir>/staging.tfplan, staging.plan.txt, staging.plan.json and the results of the checks. The plan has
# no state backend on purpose: nothing is written to S3 and no lock is taken.
set -euo pipefail
cd "$(dirname "$0")/../.."
module=deploy/terraform/aws
out="${1:-${TMPDIR:-/tmp}/costguard-staging-plan}"
mkdir -p "$out"
tf="${TERRAFORM:-terraform}"
py="${PYTHON:-python}"

vars="$out/staging.tfvars"
[ -f "$vars" ] || cp "$module/staging.tfvars.example" "$vars"

echo "=== terraform init (no backend)"
"$tf" -chdir="$module" init -backend=false -input=false -no-color > "$out/init.log"

echo "=== terraform plan"
set +e
"$tf" -chdir="$module" plan -input=false -no-color -lock=false -detailed-exitcode -var-file="$(cd "$out" && pwd)/staging.tfvars" \
  -out="$(cd "$out" && pwd)/staging.tfplan" > "$out/staging.plan.txt" 2>&1
code=$?
set -e
grep -E '^(Plan|No changes)' "$out/staging.plan.txt" || true
if [ "$code" -eq 1 ]; then
  echo "terraform plan failed, see $out/staging.plan.txt"
  tail -30 "$out/staging.plan.txt"
  exit 1
fi

"$tf" -chdir="$module" show -json "$(cd "$out" && pwd)/staging.tfplan" > "$out/staging.plan.json"

echo "=== plan checks (tools/terraform/check_plan.py)"
"$py" tools/terraform/check_plan.py "$out/staging.plan.json"

if command -v checkov > /dev/null; then
  echo "=== checkov on the plan"
  checkov -f "$out/staging.plan.json" --framework terraform_plan --compact --quiet -o cli > "$out/checkov.txt" || true
  grep -E 'Passed checks' "$out/checkov.txt" || true
fi
echo "Plan and checks in $out"
