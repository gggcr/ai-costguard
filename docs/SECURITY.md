# Security

This page describes the security model as implemented, how it is tested, and what is not done yet. Report a
vulnerability privately to the repository owners, not in a public issue.

## Identity and access

- **People** sign in through OIDC (Keycloak by default; Microsoft Entra ID and Google can be brokered). The core
  validates JWT signatures (RS256 only), issuer, expiry and audience (`costguard-api`, ADR 0076: a token made for
  another client of the realm is refused) as an OAuth2 resource server; there are no sessions or
  cookies in the API, so CSRF does not apply.
- **The company of a request comes only from the token** (`tenant_id` claim, set by the realm administrator, not
  editable by the user). Ids in paths or bodies never select another company.
- **A person is identified by the token's subject**, never by the e-mail in it, so changing one's e-mail in Keycloak
  does not grant another account (fixed after an internal review, ADR 0069, migration V47).
- **Roles**: `owner`, `finance`, `it_admin`, `agent_owner`, `auditor`, `employee`, plus service roles (`connector`,
  `importer`, `forecast`). Every changing endpoint names the roles allowed (`@Manages*`, `@ErasesData`,
  `@ReadsActivity`); the last owner cannot be removed. Employees see only the cabinet and only their own data.
- **Agents** authenticate to the gateway with virtual keys (`Authorization: Bearer cg_live_…`), stored only as
  hashes (`virtual_key.hash`); a key can be revoked or rotated, and a suspended agent is refused at step 1. Provider keys never leave the
  gateway.

## Tenant isolation

Every tenant table has PostgreSQL row-level security with `FORCE ROW LEVEL SECURITY`; the core binds the company
to the transaction and connects as a role without `BYPASSRLS`. Tests run against a real PostgreSQL as that role
(`RlsRoleGuardTest`, `RlsBackupPolicyTest`, and isolation tests per module), and an object of another company
answers 404, not 403. ClickHouse readers get a row policy per company (ADR 0035). Kafka messages carry the tenant
in their key and body.

## Data protection

- Secrets of connected systems kept in the core are encrypted with AES-GCM (`CONNECTIONS_ENCRYPTION_KEY`,
  ADR 0030). Journal receipts are signed with Ed25519 and chained by hash; key rotation keeps old receipts
  verifiable (ADR 0028).
- Personal data: an inventory of every table and topic holding it (`docs/privacy/data-inventory.md`, enforced by a
  test), retention terms, erasure of an employee or a whole company across PostgreSQL, ClickHouse, Kafka
  (tombstones), files and Keycloak, and an activity log of who did what (ADR 0021, 0022).
- Nothing personal goes to NEAR: only hashes; approvers are salted hashes (ADR 0006, 0009).
- Uploaded files: a size limit (50 MB by default), stored in S3 and parsed by the ingest service, not by the core;
  CSV exports of the UI escape formula-like cells.

## Gateway safety

The gateway fails closed: a check it cannot run answers `503 check_unavailable`, a call without a known price is
refused (`price_unknown`), budgets are reserved atomically before the provider is called, and loops are detected by
operation id and prompt. Egress to paid APIs goes only to the base URL configured for each API name; an agent chooses the
path, never the host, so the proxy cannot be pointed at internal addresses (ADR 0010).

## Supply chain and CI

`.github/workflows/security.yml` runs gitleaks on the history, Trivy on the lock files, on the built jars and on
every image (critical issues with a fix fail the build; the rest is reported), and `npm audit`. GitHub Actions are
pinned by commit SHA. Minimum versions of transitive Java libraries with known advisories are pinned in
`gradle/libs.versions.toml`.

Last manual check (2026-11-23): OSV for the 281 resolved Java runtime libraries of the core and the gateway
(14 advisories found and fixed by upgrading Jackson 2, snappy-java, lz4-java and OpenTelemetry; 0 left),
`pip-audit` of the 86 packages in `uv.lock` (0), `npm audit` of the web UI (0).

## Not done yet

- No external penetration test so far; the scope for one is ready in [security/pentest-scope.md](security/pentest-scope.md).
- No SOC 2 or ISO 27001 report.
- GDPR/CCPA: the data inventory and retention are implemented; the DPA, list of subprocessors and privacy notice are
  drafts for a lawyer ([privacy/for-lawyer.md](privacy/for-lawyer.md)).
- Rate limits exist for registration and for agents; there is no general API rate limit in front of the core
  (expected from the ingress).
