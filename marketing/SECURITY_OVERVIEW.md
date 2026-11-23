# Security overview for prospective customers

What is in place, verified in the code and tests, and what is not. Details for your security team:
[docs/SECURITY.md](../docs/SECURITY.md), [docs/SECURITY_RELEASE_AUDIT.md](../docs/SECURITY_RELEASE_AUDIT.md),
[docs/legal/SECURITY_PRIVACY_OVERVIEW.md](../docs/legal/SECURITY_PRIVACY_OVERVIEW.md).

## In place

- **Tenant isolation in the database:** PostgreSQL row-level security on every company table, forced, with the
  application connecting as a role that cannot bypass it; tests prove one company cannot read another's rows.
- **Sign-in:** SSO through OIDC (Keycloak; Microsoft Entra ID and Google brokered), optional one-time codes,
  brute-force protection; tokens accepted only if signed RS256 by your realm and issued for the CostGuard API.
- **Authorization:** six roles checked on the server; employees see only their own cabinet.
- **Encryption:** TLS everywhere in transit; AWS KMS at rest for databases, storage, backups, logs and secrets.
- **Agent spending:** fail-closed gateway, hashed API keys, idempotent operations, signed receipts.
- **Payments:** card data never touches CostGuard (Stripe Checkout); webhooks verified by signature, replay-safe.
- **Web client:** content security policy, anti-framing, no inline scripts.
- **Operations:** private network, private Kubernetes API, rate limits, audit logs, automated backups, Multi-AZ
  databases in production.
- **Privacy:** data inventory, retention job, per-person export and erasure, EU or US data residency.
- **Supply chain:** dependency scans before release (0 known vulnerabilities on 2026-11-23), SBOM available.

## Not yet

- No SOC 2 or ISO 27001 report.
- No independent penetration test (scope prepared).
- Container images not scanned in CI yet (ECR scans on push in the AWS setup).
- Legal documents (DPA, privacy policy) are drafts under review.

We will answer security questionnaires honestly against this list.
