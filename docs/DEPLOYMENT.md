# Deployment

CostGuard runs on Kubernetes from one Helm chart, `deploy/helm/costguard`, synchronised by Argo CD
(`deploy/argocd`). The detailed runbooks are in [`docs/runbooks`](runbooks) (written in Russian; the commands and
values are the same in any language).

## What has been exercised and what has not

| Target | State |
|---|---|
| Local Docker Compose (`make demo`, `make demo-lite`) | the supported way to evaluate the product |
| Helm chart | `helm lint` and `helm template` with the staging and prod values in CI (`.github/workflows/deploy.yml`), plus `tools/tests/test_helm.py` |
| Terraform for Yandex Cloud (`deploy/terraform/yandex`) | `terraform fmt -check` and `terraform validate` in CI; this is the only cloud module |
| AWS, GCP, Azure | **not provided yet.** The chart itself is cloud-neutral (it needs an ingress controller, a storage class and the managed services below) |
| A production installation serving customers | none so far |

## Managed services the chart expects

PostgreSQL 16+, Kafka with a Schema Registry, ClickHouse, Redis, S3-compatible storage, an OIDC provider
(the chart can run Keycloak itself) and an SMTP relay. Each is addressed by the variables in
[CONFIGURATION.md](CONFIGURATION.md); secrets come from Kubernetes secrets or Vault ([runbooks/secrets.md](runbooks/secrets.md)).

## Install

```bash
kubectl apply -f deploy/argocd/project.yaml -f deploy/argocd/staging.yaml   # staging follows a branch
kubectl apply -f deploy/argocd/prod.yaml                                     # prod follows a release tag
```

Or without Argo CD:

```bash
helm upgrade --install costguard deploy/helm/costguard -n costguard --create-namespace \
  -f deploy/helm/costguard/values-prod.yaml --set image.tag=<release tag>
```

Optional parts are switched by values: `keda.enabled` (ingest autoscaling; needs the KEDA operator),
`mlflow.enabled` (anomaly models), `keycloak.enabled`, monitoring, logging and alerting.

## Release and rollback

1. CI builds the images tagged with the commit and runs the tests.
2. Tag `vX.Y.Z`, set `image.tag` in `values-prod.yaml` and `targetRevision` in `deploy/argocd/prod.yaml`.
3. Argo CD → `costguard-prod` → Diff → Sync. A pre-sync Job migrates ClickHouse; the core runs Flyway migrations
   of PostgreSQL at start.

Migrations only move forward and only add columns and tables, so rolling the code back one release is safe.

## Operations

- Health: `/actuator/health` (liveness and readiness groups) on the core and the gateway; Prometheus metrics at
  `/actuator/prometheus`. Alerts and dashboards ship with the chart ([runbooks/alerting.md](runbooks/alerting.md)).
- Backups: nightly encrypted dumps of PostgreSQL, ClickHouse and the files, with a tested restore
  ([runbooks/backups.md](runbooks/backups.md)).
- Smoke test after a release: [runbooks/smoke.md](runbooks/smoke.md).
- Data residency: every store lives in one region, set once in Terraform and once in the chart; a test checks
  that the two agree (`tools/tests/test_residency.py`, ADR 0024).
