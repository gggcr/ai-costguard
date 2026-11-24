# Release notes — CostGuard 1.0.0-rc.1 (release candidate)

Date: 2026-11-24. Status: **release candidate; external blockers remain** (below). Test results:
[FINAL_QA_REPORT.md](FINAL_QA_REPORT.md). Full list of changes: [CHANGELOG.md](CHANGELOG.md).

## What is new for Western customers

- English product end to end for English-speaking companies: interface, notifications, findings, cabinet notices,
  signature stamp, handover receipt, spend report; company currency, time zone and FX source (ECB).
- Subscriptions through Stripe in USD and EUR, customer portal, Plan & billing page.
- US or EU hosting on AWS (Terraform), IAM roles instead of static S3 keys.
- Security: strict JWT audience and algorithm, web security headers and CSP, rate limits, release security audit.
- Privacy: per-person export (GDPR art. 15/20, CCPA), erasure and retention as before, legal drafts.
- A validated 15-minute demo with a synthetic US company.

## Upgrade steps (from earlier builds)

1. **Before** deploying the core: add the Audience mapper `costguard-api` to every Keycloak client that calls the
   core (`costguard-frontend`, service clients) — docs/runbooks/keycloak.md. Without it every sign-in fails.
2. The core migrates the database to V53 at start (billing tables).
3. To take payments: Stripe account and settings (docs/STRIPE_SETUP.md); until then keep `PAYMENT_PROVIDER` empty.
4. On AWS: set the chart's `serviceAccount` and leave S3 keys out of the secret.

## Known issues and limitations

- Employee-cabinet labour rules are Russian (28-day leave, payroll kinds, e-signature wording):
  docs/COUNTRY_RULES_ROADMAP.md.
- Fixed 2026-11-25: mandate working hours are answered as `24/7`, `workdays` or a window in every language; the
  anomaly detector's risk reasons and finding titles and the report explanations are in the company's language.
- The release gate `tools/release/verify.sh` runs as one script in CI (Actions → release-gate, by hand). On the
  Windows build machine its steps were run one by one on 2026-11-25; see FINAL_QA_REPORT.md.
- The AI gateway also runs without Kafka for small installs (`EVENTS_TRANSPORT=direct`, ADR 0080); file parsing,
  the anomaly detector and the NEAR notary need the full stack.

## External blockers before a commercial launch

| Blocker | Owner |
|---|---|
| Legal entity outside Russia to contract with customers, Stripe and AWS | owner |
| Written copyright assignments from both Git authors; consent to the license change; NEAR hackathon terms | owner, counsel |
| Legal review of docs/legal (privacy policy, DPA, subprocessors, procedures) | counsel |
| Stripe account, products, keys, webhook; one real test-mode run | owner |
| AWS account; `terraform apply` to staging, then prod; DNS and certificates | owner |
| ClickHouse and Schema Registry choice on AWS | engineering, after the account exists |
| Independent penetration test; SOC 2 roadmap | owner |
