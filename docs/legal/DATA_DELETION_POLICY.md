# Data deletion policy (draft)

> DRAFT FOR LEGAL REVIEW. Describes the deletion features as implemented (ADR 0021, `PrivacyService`).

## Deleting one person's data

- **Who:** the customer's owner (`POST /api/v1/employees/{id}/erasure`), after the person is dismissed in
  CostGuard; the last owner of a company cannot be erased.
- **What:** every module deletes or anonymizes what it holds about the person, found by employee id, their e-mail
  addresses and their identity-provider accounts; their Keycloak account is deleted. Records that must show "someone
  decided" keep a pseudonym (`erased:<id>`) instead of the person.
- **What stays, and until when:** accounting documents (handover acts, uploaded invoices) until their retention
  period ends (see the retention policy); messages in Kafka and service logs until they expire (7 and 30 days).
  The erasure record lists each of these with its date.
- **Record:** each erasure returns and keeps a record of what was deleted, anonymized, kept and what needs a manual
  step (counts, never content): `GET /api/v1/erasures/{id}`.
- **Repeating** an erasure returns the first record; it is not an error.

## Deleting a company

- The owner requests it (`POST /api/v1/company/erasure`); after `PRIVACY_COMPANY_GRACE` (30 days) every row of the
  company is deleted in every module, compacted Kafka records get tombstones, and the request can be cancelled until
  then (`DELETE /api/v1/company/erasure`).
- Backups: deleted data disappears from backups as they expire (RDS automated backups, S3 dumps); backups are not
  edited. Counsel: confirm this is acceptable and state it in the DPA.

## Automatic deletion

The retention job deletes what is past its term every night (retention policy). It records erasures of dismissed
employees with the reason `retention`.

## Outside CostGuard

Data the customer's systems hold (Entra ID, Google Workspace, accounting systems) is not deleted by CostGuard.
Stripe keeps billing records under its own policy. AI providers keep prompts under their own terms.
