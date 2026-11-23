#!/usr/bin/env bash
# The release gate: every check a release candidate must pass, in one run, stopping at the first failure.
#
#   tools/release/verify.sh            # from the repository root; Docker must run (Testcontainers tests)
#
# Needs JDK 25, Python with `uv`, Node 24, helm, terraform. It changes nothing: no build artefact is published,
# no tag is made. docs/RELEASE_PROCESS.md says what to do after it passes.
set -euo pipefail
cd "$(dirname "$0")/../.."

step() { printf '\n=== %s\n' "$*"; }

step "Java: build, unit, integration and Docker tests, OpenAPI contract"
./gradlew --console=plain build

step "Python: lint and tests"
uv run ruff check .
uv run pytest -q -p no:cacheprovider

step "Web client: type check, tests, production build"
(cd apps/frontend && npm ci && npx tsc --noEmit -p . && npx vitest run && npm run build)

step "Generated API types match the specification"
uv run python tools/codegen/openapi_ts.py
git diff --exit-code -- apps/frontend/src/api/generated

step "Helm chart renders for every environment"
helm lint deploy/helm/costguard
for values in deploy/helm/costguard/values-*.yaml; do
  helm template rc deploy/helm/costguard -f "$values" > /dev/null
done

step "Terraform modules are formatted and valid"
for module in deploy/terraform/*/; do
  (cd "$module" && terraform fmt -check -recursive && terraform init -backend=false -input=false > /dev/null \
     && terraform validate)
done

step "Dependency audits"
(cd apps/frontend && npm audit --omit=dev)
uv export --no-hashes --format requirements-txt > /tmp/costguard-requirements.txt
uvx pip-audit -r /tmp/costguard-requirements.txt --no-deps

step "SBOM"
./gradlew -q :core:dependencies :gateway:dependencies --configuration runtimeClasspath > /tmp/costguard-java-deps.txt
uv run python tools/release/sbom.py --java-deps /tmp/costguard-java-deps.txt

printf '\nRelease gate passed. Next: docs/RELEASE_PROCESS.md, "After the gate".\n'
