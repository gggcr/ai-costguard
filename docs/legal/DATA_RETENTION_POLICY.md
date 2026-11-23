# Data retention policy (draft)

> DRAFT FOR LEGAL REVIEW. The periods below are the **defaults in the code**
> (`apps/backend/core/src/main/resources/application.yaml`, `costguard.privacy`), enforced daily by the retention
> job (03:30). Each is configurable per installation. Counsel must confirm them against the laws of the customer's
> country (accounting, labour, tax).

| Data | Default | Variable | What happens at the end |
|---|---|---|---|
| Personal data of a dismissed employee (name, e-mail, position; licenses, devices, requests) | 1 year after dismissal | `PRIVACY_RETAIN_DISMISSED_EMPLOYEES` | erased or anonymized by every module (as a manual erasure) |
| Activity of people in connected systems (last sign-ins) | 1 year | `PRIVACY_RETAIN_ACTIVITY` | deleted |
| In-app notifications | 1 year | `PRIVACY_RETAIN_NOTIFICATIONS` | deleted |
| Equipment handover acts (PDF with names) | 5 years | `PRIVACY_RETAIN_HANDOVER_ACTS` | deleted (accounting document; Counsel: period per country) |
| Uploaded files (invoices, statements) | 5 years | `PRIVACY_RETAIN_IMPORT_FILES` | deleted |
| Action log of users ("who changed what") | 3 years | `PRIVACY_RETAIN_ACTION_LOG` | deleted |
| A company that left: everything | 30 days grace after the erasure request | `PRIVACY_COMPANY_GRACE` | deleted completely, including backups as they expire |
| Kafka messages | 7 days | topic settings | expire |
| Service logs | 30 days (staging), 365 days in the prod examples of AWS | `log_retention_days` | expire; personal data is masked in logs |
| Database backups (RDS automated) | 7 days (staging), 30 days (prod examples) | `backup_retention_days` | expire |
| Backup dumps in S3 | as above | `backup_retention_days` | expire by bucket lifecycle |
| Billing records (plan payments) | for the life of the customer account | — | deleted with the company; Stripe keeps its own records |
| AI answer cache | 24 hours | gateway | expires |

Exceptions: a legal hold requested in writing by the customer or required by law suspends deletion of the named
data until released (`[PROCESS TBD]`).
