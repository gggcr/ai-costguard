# Release process

## Versions

Semantic versions on the whole product, as Git tags: `vMAJOR.MINOR.PATCH`, release candidates
`vMAJOR.MINOR.PATCH-rc.N`. The Helm chart's `version`/`appVersion` follow the tag; images are tagged with the
commit (CI) and, for a release, also with the version. Components keep their own package versions.

## Steps

1. **Freeze**: the release branch is `goidochka`; only fixes after the freeze.
2. **Gate**: `tools/release/verify.sh` — the Java build with all tests (Docker tests included) and the OpenAPI
   contract, Python lint and tests, the web client's type check, tests and build, generated API types, Helm render
   for every environment, Terraform `fmt`/`validate`, dependency audits, the SBOM. Any failure stops the release.
3. **Demo check** on a clean stack: `make down`, remove the volumes, `make demo`,
   `python tools/demo/check_demo.py` (all steps pass) and `E2E=1 pytest tests/e2e`.
4. **Migrations**: Flyway runs them at core start; the clean stack in step 3 proves they apply from zero. For an
   upgrade, restore a copy of production on staging and start the new core against it.
5. **Notes**: update `docs/CHANGELOG.md` and `docs/RELEASE_NOTES.md` (what changed, upgrade steps, known issues).
6. **Tag**: `git tag -a vX.Y.Z-rc.N -m "…"` and push the tag. A final `vX.Y.Z` only when the blockers in
   RELEASE_NOTES are closed.
7. **Deploy**: staging first (Argo CD `deploy/argocd/staging.yaml`), smoke test (`tests/smoke`), then production
   by changing the image tag in `values-prod.yaml`.

## Upgrade notes that block a release if missed

- ADR 0076: add the `costguard-api` audience mapper in Keycloak **before** the core of this release starts.
- ADR 0077: with `PAYMENT_PROVIDER=stripe` the core refuses to start without the Stripe settings.
- Migration V53 (billing tables) runs automatically.

## Rollback

- Application: `helm rollback` / Argo CD to the previous revision. Database migrations only add tables and columns;
  rolling the application back over them works, rolling the database back needs a restore.
- Infrastructure: revert the Terraform change; never apply a plan that replaces a database without a snapshot.
- Keycloak: the audience mapper is harmless to older cores; keep it.
