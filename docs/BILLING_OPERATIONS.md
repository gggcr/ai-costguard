# Billing operations

How plans, subscriptions and payments behave once Stripe is connected ([STRIPE_SETUP.md](STRIPE_SETUP.md),
[ADR 0037](adr/0037-pricing-plans-and-limits.md), [ADR 0077](adr/0077-stripe-subscriptions.md)).

## The lifecycle of a company

| Stage | What the company sees | What decides it |
|---|---|---|
| Trial | 14 days (`PLAN_TRIAL_LENGTH`), trial limits | registration |
| Checkout | the owner chooses a plan and currency on **Plan & billing**, pays on Stripe's page | `POST /api/v1/billing/checkout` |
| Active | plan limits; access until the end of the paid period | verified `checkout.session.completed`, then `invoice.paid` each month |
| Payment failed | works on; status *payment failed, the provider will retry* | `invoice.payment_failed`: access until the end of the paid period + `PAYMENT_GRACE` (7 days) |
| Cancelled | works to the end of the paid period | `customer.subscription.updated` (cancel at period end) / `deleted` |
| Read-only | sees everything, changes nothing (`402 plan_read_only`); the gateway refuses agents | `access_until` passed (ADR 0037) |

Owners are warned 3 days before access ends (`PLAN_WARN_BEFORE`) by the feed, e-mail and Telegram, and again when
it ends. Paying again (a new checkout, or the portal's card update that lets Stripe's retry succeed) restores
access at once.

## Roles

- **Owner**: pays, opens the Stripe portal (change plan, card, cancel, invoices).
- **Finance**: sees the plan and the payment history.
- Everyone else: no billing pages.

## Where the truth is

- **Stripe** holds cards, invoices, tax and refunds. CostGuard never sees a card number.
- **The core** holds the plan, the access period and a history of payments (`GET /api/v1/billing/payments`).
  It changes them only after a webhook whose signature it verified. The browser returning with
  `?checkout=success` changes nothing by itself.

## Routine tasks

**A customer says they paid but the plan did not change.**
1. Stripe Dashboard → Developers → Webhooks → the endpoint → recent deliveries: is there a failed delivery?
   - `400`: the signature failed. Check `STRIPE_WEBHOOK_SECRET` (test vs. live, the right endpoint).
   - `409 subscription_unknown`: an event arrived before the checkout's event; Stripe retries it on its own.
   - `5xx` or timeout: the core was down; Stripe retries for up to 3 days, or press **Resend**.
2. The core's activity log has `plan.paid` / `plan.renewed` entries for the company.

**Refunds and credits.** Do them in Stripe. A refund does not shorten access in CostGuard; if access must end,
cancel the subscription in Stripe (immediately) and the plan ends with the paid period.

**Changing prices.** Create a new price in Stripe, set the variable and the catalog amount, restart the core.
Existing subscribers stay on the old price until you migrate them in Stripe.

**A company that existed before plans** has no plan row and no limits (ADR 0037). An operator assigns a plan; it
can then subscribe like any other.

**Duplicate deliveries** are harmless: every event id is stored with the change it made, and a repeat is skipped.

## Monitoring

- Alert on webhook delivery failures in Stripe (Dashboard → Developers → Webhooks → alerts).
- The core logs `Stripe <path> answered <status> (<code>)` when a Stripe API call fails (no request bodies, no
  card data). A checkout then answers `502 payment_provider_failed` and nothing is charged.

## Data

`plan_subscription` (Stripe subscription and customer ids, status, paid-until), `plan_subscription_ref` and
`plan_billing_event` (ids only), `plan_payment` (amounts and statuses). None of it is personal data of employees;
deleting a company deletes all of it ([docs/privacy/data-inventory.md](privacy/data-inventory.md)). Stripe keeps
its own records under its own retention.
