# GDPR data subject request procedure (draft)

> DRAFT FOR LEGAL REVIEW. In the hosted service the customer (the employer) is the **controller** of its employees'
> data and `[COMPANY LEGAL NAME]` is the **processor** (DPA). Requests of employees go to the employer; the processor
> assists (GDPR art. 28(3)(e)).

## If a request reaches `[COMPANY LEGAL NAME]` directly

1. Log it (date, channel, requester, company named) in `[TICKET SYSTEM]` within 1 business day.
2. Do not act on the data. Tell the requester that their employer decides, and forward the request to the
   customer's privacy contact (from the DPA) within `[3]` business days.
3. Assist the customer as below when it asks.

## How the customer answers with CostGuard

| Right (GDPR) | How | Time |
|---|---|---|
| Access (art. 15), portability (art. 20) | Owner: `GET /api/v1/employees/{id}/personal-data` — every module's rows about the person as JSON (ADR 0079). Secrets and file contents are left out; uploaded documents the person signed can be downloaded from the documents page. The export is recorded in the activity log | minutes |
| Rectification (art. 16) | Edit the employee in **Org structure and access**; data from a connected system is corrected in that system and comes back with the next sync | minutes |
| Erasure (art. 17) | Dismiss the person, then `POST /api/v1/employees/{id}/erasure` (owner); the erasure record shows what was kept for legal reasons and until when | minutes |
| Restriction (art. 18) | No dedicated switch. Counsel/product: decide whether deactivating the user and pausing connectors suffices | manual |
| Objection (art. 21), automated decisions (art. 22) | CostGuard makes no decisions with legal effect on employees; the audit flags costs, people decide | n/a |

Deadline: one month from receipt by the controller (art. 12(3)); the processor answers the controller's request for
assistance within `[5]` business days.

## Identity check

The customer verifies the requester's identity. `[COMPANY LEGAL NAME]` never sends exports to a requester directly.

## Records

Keep the request, the customer's instruction and the date of completion for `[3]` years.
