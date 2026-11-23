# Legal and privacy documents (drafts)

> **DRAFTS FOR QUALIFIED LEGAL REVIEW. NOT LEGAL ADVICE.** Written by the engineering team from what the software
> actually does. They are templates for counsel, not documents anyone has signed or adopted. CostGuard does **not**
> claim GDPR or CCPA compliance because these files exist. Every `[PLACEHOLDER]` must be filled in by the operating
> company; every "Counsel:" note is an open question.

| Document | For whom | Status |
|---|---|---|
| [PRIVACY_POLICY.md](PRIVACY_POLICY.md) | visitors and users of the service (B2B) | draft |
| [DPA.md](DPA.md) | customers (controllers) — CostGuard acts as processor | draft |
| [SUBPROCESSORS.md](SUBPROCESSORS.md) | customers; part of the DPA | draft, facts from the code and the AWS module |
| [DATA_RETENTION_POLICY.md](DATA_RETENTION_POLICY.md) | customers, auditors | draft, periods from the configuration |
| [DATA_DELETION_POLICY.md](DATA_DELETION_POLICY.md) | customers, auditors | draft, matches the erasure feature |
| [GDPR_DSR_PROCEDURE.md](GDPR_DSR_PROCEDURE.md) | support and the customer's privacy contact | draft |
| [CCPA_PROCEDURE.md](CCPA_PROCEDURE.md) | support, US customers | draft |
| [SECURITY_PRIVACY_OVERVIEW.md](SECURITY_PRIVACY_OVERVIEW.md) | security questionnaires | draft |

Facts these drafts rely on: [docs/privacy/data-inventory.md](../privacy/data-inventory.md) (every table, what it holds,
how long), ADR 0021–0024 (retention, erasure, residency), ADR 0079 (export), ADR 0078 (AWS regions), ADR 0077
(Stripe).

## Placeholders used

| Placeholder | Meaning |
|---|---|
| `[COMPANY LEGAL NAME]`, `[COMPANY ADDRESS]`, `[REGISTRATION NUMBER]` | the legal entity that operates CostGuard (not yet chosen) |
| `[PRIVACY CONTACT EMAIL]`, `[DPO NAME AND CONTACT]` | privacy contact; a DPO if required |
| `[EU REPRESENTATIVE]` | GDPR art. 27 representative, if the operator is outside the EU |
| `[GOVERNING LAW]`, `[COURTS]` | chosen by counsel |
| `[EMAIL PROVIDER]` | the SMTP service for notification e-mails (not chosen) |
| `[EFFECTIVE DATE]` | when a document is adopted |
