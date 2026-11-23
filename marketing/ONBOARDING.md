# Onboarding guide

For a new customer of the hosted service. About one hour of the customer's time.

## Day 1 — account (10 minutes)
1. Sign up at the web address you were given: company name, country, currency, time zone, language.
2. Invite colleagues: **Org structure and access → Users**; choose roles (owner, finance, IT admin, agent owner,
   auditor). Owners pay and manage privacy requests.
3. Optional: **Connect Microsoft 365 sign-in** so people use their work accounts.

## Day 1 — software spend (30 minutes)
4. **Connected systems:** connect your identity provider (Entra ID, Google Workspace or Okta) — CostGuard reads users,
   groups and app assignments; device management (Intune or Jamf) for hardware.
5. **Data import:** upload last month's SaaS invoices or a bank statement; check the suggested column mapping.
6. **Subscriptions and contracts:** confirm the vendors, seats and renewal dates CostGuard found.
7. **Findings** appear within minutes; assign owners. Savings count once they show in the next invoice.

## Week 1 — AI agents (20 minutes per agent)
8. **Mandates and rules:** create a mandate (what agents may buy, limits, approval threshold); publish it; use
   **Simulate** to test it.
9. **AI agents → Connect agent:** name, department, owner, mandate, monthly limit; issue a key (`cg_live_…`).
10. Change the agent to call the gateway before paying (SDK or HTTP, docs/API.md); model calls can go through the
    gateway too.
11. Watch **Operations journal** and **Approvals**; set notification channels (e-mail, Telegram).

## Billing
12. **Plan & billing:** choose Start or Business and currency, pay on Stripe's page; manage the card, invoices,
    plan changes and cancellation in the billing portal.

## Help
`[SUPPORT EMAIL]`, response within `[1 business day]`. Troubleshooting: docs/TROUBLESHOOTING.md.
