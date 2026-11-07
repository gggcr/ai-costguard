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

from costguard_reports import report


def fetch(core: httpx.Client, months: int) -> tuple[str, str, list[tuple[date, Decimal]], dict]:
    summary = core.get("/api/v1/analytics/summary", params={"months": months})
    summary.raise_for_status()
    company = core.get("/api/v1/company")
    info = company.json() if company.status_code == 200 else {}
    body = summary.json()
    series = [(date.fromisoformat(m["month"] + "-01"), Decimal(str(m["amount"]))) for m in body["agentSpend"]]
    return info.get("name", "компании"), info.get("currency", "RUB"), series, body.get("findings", {})


def fetch_clickhouse(clickhouse: httpx.Client, tenant: str, currency: str, months: int) -> list[tuple[date, Decimal]]:
    """Monthly totals in one currency; records in other currencies are left out, not converted."""
    query = ("SELECT toString(month), toString(sum(billed)) FROM spend_by_month "
             "WHERE tenant_id = {tenant:UUID} AND currency = {currency:String} "
             "AND month >= addMonths(toStartOfMonth(today()), -{back:UInt32}) GROUP BY month ORDER BY month "
             "FORMAT TSV")
    r = clickhouse.post("/", params={"param_tenant": tenant, "param_currency": currency, "param_back": months - 1},
                        content=query.encode())
    r.raise_for_status()
    out = []
    for line in r.text.splitlines():
        month, total = line.split("\t")
        out.append((date.fromisoformat(month), Decimal(total)))
    return out


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
    company, currency, series, findings = fetch(core, args.months)
    if args.tenant and os.environ.get("CLICKHOUSE_URL"):
        clickhouse = httpx.Client(base_url=os.environ["CLICKHOUSE_URL"], timeout=60,
                                  auth=(os.environ.get("CLICKHOUSE_USER", "default"),
                                        os.environ.get("CLICKHOUSE_PASSWORD", "")))
        series = fetch_clickhouse(clickhouse, args.tenant, currency, args.months)
    r = report.build(company, currency, series, findings, date.today(), args.horizon)
    args.out.write_bytes(report.xlsx(r) if args.format == "xlsx" else report.pdf(r))
    print(f"{args.out}: {len(series)} months, forecast {r.forecast.method}")


if __name__ == "__main__":
    main()
