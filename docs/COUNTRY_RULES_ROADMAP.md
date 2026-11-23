# Country rules: what is supported and the roadmap (stage C)

The employee cabinet has rules that differ by country: annual leave, payroll notices, electronic signatures. Today
they follow **Russian law only**. This document says what that means for US and EU customers and what is needed
before other countries can be represented as supported. No new legal rule was implemented on 2026-11-23: the
requirements below need a qualified source per country, which the project does not have.

## Supported today

| Area | Rule in the code | Where | Country |
|---|---|---|---|
| Annual leave allowance | 28 calendar days a year (Labour Code of the RF, art. 115) | `VacationService.DAYS_PER_YEAR` | Russia |
| Counting leave days | calendar days, holidays not excluded | `VacationService` | Russia |
| Payroll notices | kinds: advance, salary, vacation pay, bonus — the Russian twice-a-month pattern | `PayrollService`, `PayrollFile` | Russia (texts in English for English companies, ADR 0074) |
| Electronic signature | simple electronic signature (63-FZ arts. 5, 9) with a PIN or a TOTP code, stamp with hash | `SignatureStamp` | Russia; the English stamp names no law and says applicability depends on the law of the document |
| Equipment handover act | Russian act; English "equipment handover receipt" with no legal references | `HandoverActPdf` | Russia; English wording generic |
| Money, dates, time zone, language of texts | per company (ADR 0072–0074) | everywhere | all countries |
| FX rates | Bank of Russia or ECB (ADR 0075) | catalog | all countries |

**For US and EU customers:** use the software, AI-agent, findings, assets, billing and organisation features. Do not
rely on the cabinet's leave allowance or payroll-notice kinds as statements of local law; the leave balance shows
28 days. The sales material must not claim local labour-law support.

## Roadmap

### C1. Make the rules configurable per company (engineering, no legal content)
- Move `DAYS_PER_YEAR` into a company setting (default by country, editable by the owner), with day counting
  `calendar` or `working` and a holiday calendar source.
- Payroll notice kinds as a list per company.
- Tests for boundaries: leave across year end, half days, carry-over, a leave request touching a holiday.

### C2. Country profiles (needs legal input per country)
| Country | Needed from a qualified source |
|---|---|
| United States | No federal paid-leave minimum; state and city paid sick-leave laws (e.g. California, New York); company policy decides vacation. Pay frequency rules by state. |
| Germany | Minimum leave (Bundesurlaubsgesetz: working days, 5- vs 6-day week), carry-over and expiry rules, holidays by federal state. |
| France, Netherlands, Ireland, others | the same questions per country |

Each profile: the parameters, the source (statute and date), an owner who reviews changes yearly.

### C3. Electronic signatures
- US: ESIGN Act and UETA — consent to electronic records, record retention, the ability to keep a copy;
  exclusions (wills, some notices). EU: eIDAS — the current signature is at most a simple electronic signature;
  advanced or qualified signatures need a trust service provider.
- Requirement: counsel's opinion per document type before the cabinet's signature is offered for US/EU employment
  documents. Engineering then adds a consent step and per-country stamp wording.

### C4. Documents
- English handover receipt reviewed by counsel; templates per country if needed.

Missing requirements (blockers for C2–C4): a list of target countries from sales, legal sources per country, and
counsel's review of signature use. Until then the product is represented as supporting Russian labour rules only.
