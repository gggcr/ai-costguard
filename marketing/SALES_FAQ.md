# Sales FAQ

**Who uses CostGuard today?** Nobody in production yet. The product is a release candidate with a scripted demo on
synthetic data. We say so up front.

**How long does setup take?** The hosted service: sign up, connect your identity provider and upload a month of
invoices; the first audit findings appear within minutes. Agent governance needs your agents to call the gateway
(an SDK for Python and JavaScript, or plain HTTP) — typically a small change per agent.

**Do you see our AI prompts?** Only if your agents send model calls through the gateway: prompts are forwarded to the
provider you configure, and the provider's answer is kept for 24 hours so that a retried request (same
Idempotency-Key) is not paid twice. Spend authorizations do not need prompts at all.

**Where is our data?** In the AWS region of your installation: US (Virginia, Ohio or Oregon) or EU (Frankfurt,
Ireland or Stockholm). EU and US installations share nothing.

**Is it GDPR / CCPA compliant?** We built the features compliance needs — data inventory, retention, per-person
export and erasure, regional hosting, a DPA draft and subprocessor list — but we do not claim compliance: that
depends on your use and on the legal review of our documents, which is pending.

**SOC 2? Pen test?** Neither yet. An internal security review and automated scans are documented; an independent
penetration test is planned before general availability. See [SECURITY_OVERVIEW.md](SECURITY_OVERVIEW.md).

**Can we self-host?** Yes, on Kubernetes (Helm chart, Terraform for AWS). Enterprise plan.

**What if an agent goes rogue?** The gateway refuses before money moves: no mandate, over budget, a loop, a risky
pattern or a task mismatch each stop it; above a threshold a person approves. An agent can be suspended instantly.

**Which systems do you connect to?** Microsoft Entra ID and Intune, Google Workspace, Okta, Jamf, Merge.dev
(a unified API to many HRIS and accounting systems), mailbox for invoices, bank statements (CSV, MT940), plus generic CSV/Excel import.

**What does it cost?** $149 or $699 per month ([PRICING.md](PRICING.md)); 14-day trial.

**Can we cancel?** Yes, in the billing portal; access runs to the end of the paid month, then the account is
read-only; you can export and ask us to delete everything.

**Is the product for sale as a company/codebase?** Yes; see [BUYER_BRIEF.md](BUYER_BRIEF.md) and the IP checklist
(ownership documents are still being completed).
