# Changelog

The product has no tagged release yet; this lists what changed by milestone. Every decision has an ADR in
[`docs/adr`](adr).

## Unreleased: readiness for Western companies (2026-11-22 to 2026-11-23)

- **Money in the company's currency** instead of roubles everywhere: budgets, prices, findings, savings and the
  gateway; foreign amounts are converted at the rate of their day; amounts and dates in uploaded files are read the
  way the company's country writes them (ADR 0072).
- **The company's time zone**: "today", monthly boundaries, pay days and morning reminders follow it; the
  gateway's working hours of a mandate too (ADR 0073).
- **The company's language**: notifications, e-mails, Telegram replies, audit findings, the agent traffic light and
  license leases are written in English for English-speaking companies; the UI starts in the browser's language
  and writes numbers, money and dates by the UI language (ADR 0074).
- **An American demo company**, Brightline Analytics Inc. (USD, English), next to the Russian one; all synthetic.
- **Security**: Jackson 2, OpenTelemetry, snappy-java and lz4-java upgraded past 14 known advisories (9 high).
- **Documentation in English**: product overview, architecture, installation, configuration, deployment, security,
  API, troubleshooting; buyer materials in `marketing/`.

## Security review (2026-11-12 to 2026-11-14)

- A person is identified by the token's subject, not its e-mail (ADR 0069).
- Payroll is paid only to an unambiguous employee (ADR 0070).
- Limits on parsing `.xlsx`/`.csv`, the actuator off the public ingress, and other findings (ADR 0071).

## Employee cabinet (ADR 0051–0068)

Employee role and cabinet, documents and handover acts, vacations, payroll, the license pool and leases, purchase
requests checked against subscriptions, shadow spend, Microsoft 365 sign-in and provisioning, the agents' budget
traffic light, simple electronic signatures.

## Platform (ADR 0021–0050)

Personal-data retention and erasure, activity log, log masking, data residency, backups, alerting, CI security
checks, secret rotation, smoke tests, encrypted connector secrets, notifications, more audit rules, invoices and
payments, forecasts, ingress and Keycloak in the cluster, self-registration and invitations, plans and trials, a
payment-provider interface, idempotent gateway answers, budget counters rebuilt from the ledger, hardware imports,
FOCUS cloud exports, the SCIM server, observability with OpenTelemetry.

## Foundation (ADR 0001–0020)

The modular core with row-level security, Kafka and Avro contracts, the gateway in the agent's path with budgets in
Redis, the audit engine, the NEAR notary (Merkle roots, mandate hashes, confirmations), ClickHouse, paid-API
egress, the AI assessor, OCR and LLM invoice extraction, connectors (Merge, T-Bank and others), agent risk scoring
with MLflow, KEDA, contract tests, connected systems.
