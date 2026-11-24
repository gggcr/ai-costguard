"""costguard-report: fetches the spend summary from the core and writes the report.

    CORE_URL=https://core… CORE_TOKEN=… costguard-report --format pdf --months 12 --out report.pdf

With CLICKHOUSE_URL and --tenant the monthly spend comes from ClickHouse (spend_by_month: invoices, payments,
subscriptions and agents); without them, from the core, which knows agent spend only. Company name and findings
always come from the core. Airflow runs this every month (infra/airflow/dags/monthly_report.py).
"""

from __future__ import annotations

import argparse
import os
from datetime import date
from decimal import Decimal
from pathlib import Path

import httpx

from costguard_common import llm, tracing
from costguard_reports import explain, report


def fetch(core: httpx.Client, months: int) -> tuple[str, str, list[tuple[date, Decimal]], dict, str]:
    """The company's name, currency, monthly agent spend, findings and language (ADR 0074: ru or en)."""
    summary = core.get("/api/v1/analytics/summary", params={"months": months})
    summary.raise_for_status()
    company = core.get("/api/v1/company")
    info = company.json() if company.status_code == 200 else {}
    body = summary.json()
    series = [(date.fromisoformat(m["month"] + "-01"), Decimal(str(m["amount"]))) for m in body["agentSpend"]]
    language = str(info.get("language") or "ru").lower()
    return (info.get("name") or report.TEXT[report._lang(language)]["company"], info.get("currency", "RUB"), series,
            body.get("findings", {}), language)


def fetch_clickhouse(clickhouse: httpx.Client, tenant: str, currency: str, months: int,
                     tenant_setting: bool = False) -> list[tuple[date, Decimal]]:
    """Monthly totals in one currency; records in other currencies are left out, not converted. With tenant_setting
    the query names the company in SQL_tenant_id, as the row policies of a reader ask (ADR 0035)."""
    query = ("SELECT toString(month), toString(sum(billed)) FROM spend_by_month "
             "WHERE tenant_id = {tenant:UUID} AND currency = {currency:String} "
             "AND month >= addMonths(toStartOfMonth(today()), -{back:UInt32}) GROUP BY month ORDER BY month "
             "FORMAT TSV")
    params = {"param_tenant": tenant, "param_currency": currency, "param_back": months - 1}
    if tenant_setting:
        params["SQL_tenant_id"] = tenant
    r = clickhouse.post("/", params=params, content=query.encode())
    r.raise_for_status()
    out = []
    for line in r.text.splitlines():
        month, total = line.split("\t")
        out.append((date.fromisoformat(month), Decimal(total)))
    return out


@tracing.job("costguard-report", "report")
def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="costguard-report")
    parser.add_argument("--format", choices=["xlsx", "pdf"], required=True)
    parser.add_argument("--months", type=int, default=12)
    parser.add_argument("--horizon", type=int, default=3)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--tenant", help="tenant id, to read all costs from ClickHouse")
    args = parser.parse_args(argv)
    core = httpx.Client(base_url=os.environ["CORE_URL"], timeout=60,
                        headers={"Authorization": f"Bearer {os.environ['CORE_TOKEN']}"})
    company, currency, series, findings, language = fetch(core, args.months)
    if args.tenant and os.environ.get("CLICKHOUSE_URL"):
        clickhouse = httpx.Client(base_url=os.environ["CLICKHOUSE_URL"], timeout=60,
                                  auth=(os.environ.get("CLICKHOUSE_USER", "default"),
                                        os.environ.get("CLICKHOUSE_PASSWORD", "")))
        series = fetch_clickhouse(clickhouse, args.tenant, currency, args.months,
                                  os.environ.get("CLICKHOUSE_TENANT_SETTING") == "1")
    r = report.build(company, currency, series, findings, date.today(), args.horizon, language)
    text = explain.explain(r, model(os.environ, language), f"report-{date.today():%Y-%m}")
    args.out.write_bytes(report.xlsx(r, text.text) if args.format == "xlsx" else report.pdf(r, text.text))
    print(f"{args.out}: {len(series)} months, forecast {r.forecast.method}, explanation by {text.source}")


def model(env, language: str = "ru") -> llm.GatewayLlm | None:
    """The model of the explanations, through the gateway; none unless REPORTS_LLM_API_KEY and _MODEL are set. The
    task goal the gateway checks the call against is in the company's language, as people read it in approvals."""
    key, name = env.get("REPORTS_LLM_API_KEY"), env.get("REPORTS_LLM_MODEL")
    if not key or not name:
        return None
    client = httpx.Client(base_url=env.get("REPORTS_LLM_GATEWAY_URL", "http://gateway:8081"), timeout=60)
    return llm.GatewayLlm(client, key, env.get("REPORTS_LLM_PROVIDER", "openai"), name,
                          goal=explain.task_goal(language), max_tokens=600,
                          folder_id=env.get("REPORTS_LLM_FOLDER_ID", ""))


if __name__ == "__main__":
    main()
