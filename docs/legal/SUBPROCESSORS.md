# Subprocessor register (draft)

> DRAFT FOR LEGAL REVIEW. Lists third parties that may process personal data of customers' employees when CostGuard
> is run as a hosted service by `[COMPANY LEGAL NAME]` on AWS. A customer who runs CostGuard on its own
> infrastructure is its own operator and this list does not apply to it.

Derived from the code (`apps/backend/gateway/src/main/resources/application.yaml`, `deploy/terraform/aws`,
`docs/privacy/data-inventory.md`). "Optional" means it processes data only if the customer turns it on.

| Subprocessor | Purpose | Personal data | Location | Optional | Transfer mechanism (EU data) |
|---|---|---|---|---|---|
| Amazon Web Services, Inc. (and its EU affiliates) | hosting: compute, databases, storage, backups, logs | all data of the service | the installation's region: US (`us-east-1`/`us-east-2`/`us-west-2`) or EU (`eu-central-1`/`eu-west-1`/`eu-north-1`) | no | EU installation: data stays in the EU region. Counsel: AWS DPA and SCCs for support access |
| Stripe, Inc. / Stripe Payments Europe, Ltd. | billing of the customer's subscription | the customer's billing contact, company, address, payment method (held by Stripe; CostGuard stores only ids, statuses, amounts) | Stripe's regions | no (if billing is used) | Counsel: Stripe DPA, SCCs / Data Privacy Framework |
| `[EMAIL PROVIDER]` | notification e-mails | recipient's name and e-mail, notification text | `[LOCATION]` | yes | `[TBD]` |
| Telegram FZ-LLC | notifications to a chat the user links | chat id, notification text | Telegram's infrastructure | yes, per user | Counsel: is a user-initiated link a transfer by the operator? |
| OpenAI, L.L.C. | AI model calls the customer's agents make through the gateway | whatever the agent puts in a prompt (decided by the customer); answers are cached 24 h in Redis | US | yes, per customer configuration | Counsel: OpenAI DPA; prompts may contain personal data |
| Anthropic, PBC | as above | as above | US | yes | Counsel: Anthropic DPA |
| Other AI providers configured by the customer (GigaChat, YandexGPT and others in the gateway catalog) | as above | as above | provider's location | yes | Counsel: per provider; Russian providers are not expected in EU/US installations |
| ClickHouse, Inc. (if ClickHouse Cloud is used) | analytics store of cost records | cost records may name employees (license holders) | same AWS region | depends on deployment choice | Counsel: ClickHouse DPA |
| Microsoft (Entra ID), Google (Workspace), Okta, Jamf and other systems the customer connects | **not subprocessors**: they are the customer's own systems; CostGuard reads from them on the customer's instruction | | | | |
| NEAR Protocol (public blockchain) | anchoring hashes of receipts | **none**: only hashes, no company or person (ADR 0009) | public network | yes | Counsel: confirm hashes of business records are not personal data |

Not third parties: Keycloak (identity), Kafka, PostgreSQL, Redis, ClickHouse when self-run — software operated by
`[COMPANY LEGAL NAME]` inside the AWS account.

## Change notice

`[COMPANY LEGAL NAME]` will notify customers of a new subprocessor `[30]` days before it starts processing (DPA
section 6). Changes to this file are tracked in Git.
