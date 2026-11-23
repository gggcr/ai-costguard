# Third-party licenses and distribution restrictions

Generated 2026-11-23 with `tools/release/sbom.py` (how to regenerate: see the script). Machine-readable:
[sbom/costguard.cdx.json](sbom/costguard.cdx.json) (CycloneDX 1.5) and [sbom/licenses.csv](sbom/licenses.csv).
This is an engineering inventory, not legal advice; counsel should confirm the conclusions before a sale.

## Inventory

| Ecosystem | Runtime components | Without a declared license |
|---|---|---|
| Java (core and gateway runtime classpath, from POMs) | 281 | 0 |
| npm (web client, production dependencies only) | 8 | 0 |
| Python (all packages pinned in `uv.lock`, incl. dev tools) | 86 | 0 |

The large majority are Apache-2.0, MIT, BSD or EPL. Components that carry conditions worth reading:

| Component | License | Where | What it means | Action |
|---|---|---|---|---|
| fpdf2 2.9.0 | LGPL-3.0-only | reports service (PDF of the spend report) | Used unmodified as a separate Python package. LGPL obligations: ship its license text and let users replace the library; in a container image the user can. A hosted service does not distribute it. | Keep; include `LICENSE` of fpdf2 in image notices. Replacement (reportlab BSD) is possible but not justified by risk |
| logback 1.5 | EPL-2.0 or LGPL-2.1 (dual) | core, gateway (logging) | Choose EPL-2.0; unmodified use is unproblematic | None |
| jakarta.annotation, jakarta.mail, angus-mail | EPL-2.0 or GPL-2.0 with Classpath Exception | core | Classpath Exception allows linking without copyleft effect | None |
| certifi | MPL-2.0 | Python services | File-level copyleft only if modified | None |
| DejaVu Sans font | Bitstream Vera / public-domain style license (`fonts/LICENSE`) | PDFs | Free redistribution | Keep the font license |

## Infrastructure the deployment uses (not linked into CostGuard)

| Component | License | Restriction |
|---|---|---|
| Redis 7.4 | RSALv2 / SSPLv1 | May not be offered as a managed Redis service. Running it as CostGuard's internal cache is allowed. On AWS the module uses ElastiCache (AWS's service), which avoids the question. |
| HashiCorp Vault 1.18 | BUSL-1.1 | Use in production allowed except to offer a competing hosted Vault. Local stack only; production uses the cloud's secret store. |
| Confluent Schema Registry 7.7 | Confluent Community License | May not be offered as a competing SaaS registry; internal use allowed. Apicurio Registry (Apache-2.0) is an alternative. |
| Grafana, Tempo | AGPL-3.0 | Unmodified use as internal monitoring has no source-disclosure effect on CostGuard; modifying and offering them to users would. |
| Keycloak, PostgreSQL, Kafka, ClickHouse, nginx | Apache-2.0 / PostgreSQL / BSD-style | None |

## Conclusion

No dependency requires CostGuard's own source code to be disclosed when CostGuard is sold as a hosted service or
as software delivered to a customer, provided the notices are kept and the infrastructure components above are used
internally and unmodified. Counsel: confirm, and confirm the LGPL notice handling for fpdf2 if images are delivered
to customers (on-premise sale).
