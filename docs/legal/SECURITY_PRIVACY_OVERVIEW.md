# Security and privacy overview (draft, DPA Annex 2)

> DRAFT FOR LEGAL REVIEW. Technical and organisational measures as implemented in the code on 2026-11-23. Measures
> marked *planned* do not exist yet. No certification (SOC 2, ISO 27001) and no independent penetration test exist.

## Technical
- **Isolation of customers:** every company's rows are separated by PostgreSQL row-level security, forced on every
  tenant table; the application connects as a role that cannot bypass it; ClickHouse row policies per company.
- **Access:** OIDC sign-in (Keycloak; Microsoft Entra ID and Google can be brokered), optional TOTP codes, brute-force
  protection and a password policy in production; access tokens must be issued for the CostGuard API (audience) and
  signed RS256; roles (owner, finance, IT admin, agent owner, auditor, employee) checked on the server.
- **Encryption:** TLS in transit (ingress, database, Redis, Kafka, S3 TLS-only); AWS KMS at rest for databases,
  storage, backups, logs and secrets; connection secrets encrypted by the application.
- **Network:** private subnets, security groups admitting only the cluster, private Kubernetes API, request-rate limits
  at the ingress, security headers and a content security policy on the web client.
- **Logging and audit:** action log of users (who changed what), signed receipts of agent operations, Kubernetes audit
  logs, rejected network flows.
- **Data minimisation:** the export and erasure features per person; retention job; logs mask personal data; secrets
  never in exports.
- **Availability:** Multi-AZ databases in production, automated backups (30 days in the production examples),
  versioned backup storage.
- **Software supply chain:** dependency vulnerability scans (OSV, npm audit, pip-audit) before release; images scanned
  on push to ECR; *planned:* image scanning in CI, signed images.

## Organisational (`[TO BE ESTABLISHED BY THE OPERATING COMPANY]`)
- Named security owner and privacy contact.
- Access to production limited to `[N]` named engineers with MFA; quarterly access review.
- Incident response procedure with breach notification to customers within `[48]` hours.
- Confidentiality agreements with staff and contractors.
- Annual security training.
- Independent penetration test before general availability (*planned*, scope in docs/security/pentest-scope.md).
