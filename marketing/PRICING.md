# Pricing

Monthly subscription per company, billed through Stripe in US dollars or euros, cancel any time (access runs to
the end of the paid month). Prices are the configuration in `application.yaml` and Stripe; they are a proposal
derived from the plan limits and comparable tools, **not** validated with customers yet
([PRICING_STRATEGY.md](PRICING_STRATEGY.md) explains the reasoning).

| Plan | USD / month | EUR / month | AI agents | Employees | Connected systems | Gateway operations / month |
|---|---|---|---|---|---|---|
| Trial (14 days) | free | free | 3 | 25 | 2 | 10,000 |
| Start | **149** | **139** | 10 | 100 | 5 | 100,000 |
| Business | **699** | **649** | 50 | 1,000 | 25 | 1,000,000 |
| Enterprise | on request | on request | custom | custom | custom | custom (dedicated region install, SSO setup, DPA negotiation) |

- All plans: every feature of Track A and Track B, SSO, English and Russian UI, US or EU data region.
- Over a limit: adding agents, employees or connections is refused with a clear message; gateway operations above
  the monthly allowance are refused until the next month or an upgrade. Nothing is charged automatically for overage.
- Taxes: added at checkout where applicable (Stripe Tax, once configured).
- After the trial or a lapsed payment: read-only access to all data; a failed renewal keeps the account working for
  7 days while the card is retried.

Changing prices: create a new price in Stripe and set it in the configuration (docs/STRIPE_SETUP.md); existing
subscribers keep their price until migrated.
