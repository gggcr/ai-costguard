# FAQ

**Does CostGuard see our prompts and model answers?**
The gateway proxies the call, so they pass through it. It reads token counts from the answer and records the
operation (agent, vendor, model, cost, decision), not the prompt. To answer a retried operation without paying
twice, it keeps the provider's answer in Redis for 24 hours (`costguard.gateway.answer-ttl`; answers above 1 MiB
are not kept). The optional AI assessor sends the operation and the stated task goal to a model you configure.

**What happens if the gateway is down or a check cannot run?**
Agents get `503 check_unavailable` and nothing is paid. The gateway fails closed by design. The Helm chart runs three
gateway replicas by default; budget counters live in Redis and are rebuilt from the ledger (ADR 0040).

**Which providers are supported?**
OpenAI, Anthropic, GigaChat and YandexGPT through their own APIs, plus any paid HTTP API configured as an egress
route, plus the authorize/settle API for anything else.

**How much latency does it add?**
The checks run in memory and in Redis before the call is forwarded. We have a load-test workflow
(`.github/workflows/load.yml`) but no published benchmark; measure it in your environment.

**Is the NEAR blockchain required?**
No. The journal is hash-chained and signed without it; anchoring on NEAR is an optional notary service. Only Merkle
roots and hashes go on chain — no company names, people or amounts in clear.

**Can we run it ourselves?**
Yes; that is the only way today. Docker Compose for evaluation, a Helm chart for Kubernetes. There is no hosted
offering yet.

**Which currencies, languages and countries?**
Any ISO currency per company, converted at the rate of the day, from the European Central Bank or the Bank of
Russia (one source per installation). The UI is in English and Russian; server texts follow the company's language. Files are
read by the company's country conventions (US dates are month/day/year). Vacation and payroll rules currently
follow Russian law.

**How is our data separated from other companies'?**
Each company's rows are protected by PostgreSQL row-level security, enforced for the application's own database
role; the company comes only from the signed token. Tests check that another company's objects answer 404.

**GDPR / CCPA?**
The data inventory, retention terms and erasure are implemented. A DPA, a list of subprocessors and a privacy
notice exist only as drafts for a lawyer. There is no SOC 2 or ISO 27001 report.

**Who built it, and how?**
A small team, starting at a NEAR hackathon in November 2026, mostly with AI coding assistants under the team's
direction and review. The repository's 75 ADRs and automated tests are the best way to judge the result.

**What is the license?**
MIT. See the buyer brief for what that means for an acquisition.
