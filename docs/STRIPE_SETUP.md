# Stripe setup

CostGuard sells its plans as monthly Stripe subscriptions ([ADR 0077](adr/0077-stripe-subscriptions.md)). This
guide connects a Stripe account to a CostGuard installation. Start in **test mode** and finish the checklist at the
end before you switch to live keys.

> Status: the integration is implemented and covered by automated tests against a stand-in for Stripe's API with
> real webhook signatures. It has **not** been run against a real Stripe account yet. Do the test-mode run below
> before taking money.

## What you need

- A Stripe account of a legal entity in a country Stripe supports (Stripe does not serve Russian companies).
- Access to the CostGuard core's environment (Helm secret, Vault or `.env`).
- A public HTTPS address of the core for webhooks, e.g. `https://api.costguard.example.com`. Locally, use the
  Stripe CLI (below).

## 1. Products and prices

In the Stripe Dashboard (test mode), **Product catalog → Add product**:

| Product | Price | Billing | Environment variable |
|---|---|---|---|
| CostGuard Start | 149.00 USD | recurring, monthly | `STRIPE_PRICE_START_USD` |
| CostGuard Start | 139.00 EUR | recurring, monthly | `STRIPE_PRICE_START_EUR` |
| CostGuard Business | 699.00 USD | recurring, monthly | `STRIPE_PRICE_BUSINESS_USD` |
| CostGuard Business | 649.00 EUR | recurring, monthly | `STRIPE_PRICE_BUSINESS_EUR` |

Copy each price id (`price_...`) into its variable. The amounts must match the catalog
(`costguard.plans.catalog.<plan>.prices` in `apps/backend/core/src/main/resources/application.yaml`), which is what
the plan page shows. To change a price, create a new Stripe price and change both the variable and the catalog
entry (`COSTGUARD_PLANS_CATALOG_START_PRICES_USD=…`). Existing subscriptions keep their old price until you
migrate them in Stripe.

A plan and currency without a price id is not offered for payment: a checkout answers `502` and the core logs
`no Stripe price`. Leave a variable empty only if you do not sell that combination, and remove the amount from the
catalog too.

## 2. API key

**Developers → API keys**: create a **restricted key** with write access to Checkout Sessions, Customer portal
sessions and read access to Customers, Subscriptions, Invoices, Prices. Put it in `STRIPE_SECRET_KEY`. Never put it
in the web UI, in Git or in `.env.example`.

## 3. Customer portal

**Settings → Billing → Customer portal**: enable it and allow

- updating payment methods and billing details,
- viewing invoice history,
- cancelling subscriptions (choose "at the end of the billing period"),
- switching plans: add the products above, both currencies.

The plan page's **Manage subscription** button opens this portal.

## 4. Webhook endpoint

**Developers → Webhooks → Add endpoint**:

- URL: `https://<core>/api/v1/billing/notifications/stripe`
- Events: `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
  `checkout.session.async_payment_failed`, `checkout.session.expired`, `invoice.paid`, `invoice.payment_failed`,
  `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`.

Copy the signing secret (`whsec_...`) into `STRIPE_WEBHOOK_SECRET`. The core refuses a delivery that is unsigned,
signed with another secret, or more than 5 minutes old. The endpoint's API version does not matter: the core reads
both the pre-2025 and the 2025 event shapes.

The path is open without a token (Stripe cannot log in); the signature is the authentication. Allow it through the
ingress; do not put it behind SSO.

## 5. Configure the core

```bash
PAYMENT_PROVIDER=stripe
STRIPE_SECRET_KEY=rk_test_...            # from the secret store, not from a file in Git
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PRICE_START_USD=price_...
STRIPE_PRICE_START_EUR=price_...
STRIPE_PRICE_BUSINESS_USD=price_...
STRIPE_PRICE_BUSINESS_EUR=price_...
WEB_URL=https://app.costguard.example.com   # Stripe sends the payer back to $WEB_URL/settings/plan
# optional
STRIPE_AUTOMATIC_TAX=false               # true after you set up Stripe Tax registrations
PAYMENT_GRACE=P7D                        # how long a company keeps working after a failed renewal
```

With `PAYMENT_PROVIDER=stripe` the core does not start unless the key, the webhook secret and at least one price
are set. In Helm, put the secrets in the core's secret and `PAYMENT_PROVIDER` in `core.env`.

## 6. Local testing with the Stripe CLI

```bash
stripe login
stripe listen --forward-to localhost:8080/api/v1/billing/notifications/stripe
# copy the printed whsec_... into STRIPE_WEBHOOK_SECRET and restart the core
```

Then, as an owner on `http://localhost:3000/settings/plan`, choose a plan and pay with the test card
`4242 4242 4242 4242` (any future date, any CVC). Simulate the rest:

```bash
stripe trigger invoice.payment_failed          # a failed renewal: status past_due, grace period
stripe subscriptions update <sub_id> --items[0][id]=<si_id> --items[0][price]=<business price>   # plan change
stripe subscriptions cancel <sub_id>           # cancellation: access runs to the end of the paid period
```

## Go-live checklist

- [ ] Test-mode run done: checkout, renewal (use a test clock), failed payment, plan change, cancellation.
- [ ] Live products and prices created; the live price ids set; amounts equal to the catalog.
- [ ] Live restricted key and live webhook secret stored in the secret store, not in files.
- [ ] Webhook endpoint reachable over HTTPS; Stripe's dashboard shows successful deliveries.
- [ ] Customer portal configured in live mode.
- [ ] Tax: Stripe Tax registrations and `STRIPE_AUTOMATIC_TAX=true`, or a decision documented by your accountant.
- [ ] Terms of service, refund policy and privacy notice linked from Checkout (Settings → Public details).
- [ ] `PAYMENT_PROVIDER` is never `dummy` outside the local stack.
