# CCPA / CPRA privacy rights procedure (draft)

> DRAFT FOR LEGAL REVIEW. For California employees of US customers. Under the CCPA as amended by the CPRA, the
> customer is the **business** and `[COMPANY LEGAL NAME]` acts as its **service provider** under a written contract
> (DPA with CCPA terms). Counsel: confirm thresholds, the employee-data scope after the CPRA, and other states'
> laws (e.g. Virginia, Colorado, Connecticut) where they apply to employee data.

## Commitments as a service provider (to be in the contract)

- Process personal information only to provide CostGuard to the customer; no selling or sharing (CCPA terms), no
  cross-context behavioural advertising, no combining with other customers' data.
- Notify the customer if it can no longer meet its obligations.
- Let the customer take reasonable steps to stop unauthorized use (audit rights per the DPA).

CostGuard does not sell or share personal information and runs no advertising.

## Consumer requests

Requests from employees go to the employer. If one reaches `[COMPANY LEGAL NAME]`: log it, forward it to the
customer within `[3]` business days, tell the requester it was forwarded.

| Right | How the customer answers in CostGuard | Deadline (business) |
|---|---|---|
| Right to know / access | `GET /api/v1/employees/{id}/personal-data` (owner), JSON per module | 45 days, extendable once by 45 |
| Right to delete | dismiss, then `POST /api/v1/employees/{id}/erasure`; legal retention exceptions are listed in the erasure record | 45 days |
| Right to correct | edit the employee or correct the source system | 45 days |
| Right to limit use of sensitive personal information | CostGuard does not collect sensitive personal information as defined (no health, biometrics, precise geolocation, government ids) | n/a |
| Opt-out of sale/sharing | nothing is sold or shared | n/a |
| Non-discrimination | not applicable to the processing | — |

## Categories (for the customer's notice at collection)

Identifiers (name, work e-mail), professional information (position, department, dismissal date), commercial
information about the company's spending that may name an employee (license holder, device holder), internet
activity limited to last sign-in dates to company SaaS tools, and contents of prompts the customer's AI agents send.
