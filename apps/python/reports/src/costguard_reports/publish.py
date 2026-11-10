"""costguard-forecast: forecasts a company and its departments and publishes them to the core (ADR 0034).

    CORE_URL=https://core… CORE_TOKEN=… CLICKHOUSE_URL=http://clickhouse:8123 \
        costguard-forecast --tenant <uuid> --months 24 --horizon 6

Spend by month comes from ClickHouse (spend_by_month for the company, spend_by_department_month for departments),
in the currency of the company; months before the current one, which is not over. The registry, the findings and
the names of departments come from the core. Every series is forecast by its own champion (forecast.py), the
departments are reconciled with the company (reconcile.py), each point gets the registry baseline (baseline.py),
and the expected savings go beside (savings.py). The document goes to PUT /api/v1/analytics/forecast. Airflow runs
this every week (infra/airflow/dags/forecast_weekly.py) with the token of the forecast service account.
"""

from __future__ import annotations

import argparse
import os
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

import httpx

from costguard_reports import baseline, reconcile, savings
from costguard_reports.forecast import Forecast, forecast

COMPANY = "Компания"
NO_DEPARTMENT = "Без отдела"
CENT = Decimal("0.01")
VERSION = "costguard-forecast 1"


def _month(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def _cents(x: float | Decimal) -> Decimal:
    return Decimal(str(max(float(x), 0.0))).quantize(CENT, rounding=ROUND_HALF_EVEN)


def _series(name: str, department: str | None, history: list[tuple[date, Decimal]], f: Forecast,
            values: list[Decimal], months: list[date], base: list[Decimal]) -> dict:
    points = []
    for p, value, month, b in zip(f.points, values, months, base, strict=True):
        shift = value - p.value
        points.append({"month": _month(month), "value": value, "low": _cents(p.low + shift),
                       "high": _cents(p.high + shift), "baseline": b})
    champion, challenger = f.champion, f.challenger
    return {"departmentId": department, "name": name, "model": f.method,
            "wape": champion.wape if champion else None, "mase": champion.mase if champion else None,
            "challenger": challenger.model if challenger else None,
            "challengerWape": challenger.wape if challenger else None,
            "history": [{"month": _month(m), "amount": v} for m, v in history], "points": points}


def document(currency: str, company: list[tuple[date, Decimal]], departments: dict[str, list[tuple[date, Decimal]]],
             names: dict[str, str], subscriptions: list[baseline.Subscription], findings: list[dict], today: date,
             horizon: int) -> dict:
    """The forecast document; departments are keyed by id, "" for spend with no department."""
    axis = sorted({m for m, _ in company} | {m for h in departments.values() for m, _ in h})
    if axis:
        last = axis[-1]
    else:  # no spend yet: the forecast starts this month, after the last one that is over
        last = date(today.year - 1, 12, 1) if today.month == 1 else date(today.year, today.month - 1, 1)
    months = baseline.months_after(last, horizon)

    def aligned(history: list[tuple[date, Decimal]]) -> list[tuple[date, Decimal]]:
        by = dict(history)
        return [(m, by.get(m, Decimal(0))) for m in axis]

    company_history = aligned(company)
    company_f = forecast([v for _, v in company_history], horizon)
    part_histories = {d: aligned(h) for d, h in departments.items()}
    part_f = {d: forecast([v for _, v in h], horizon) for d, h in part_histories.items()}
    _, reconciled = reconcile.reconcile([float(p.value) for p in company_f.points],
                                        {d: [float(p.value) for p in f.points] for d, f in part_f.items()},
                                        company_f.sigma ** 2, {d: f.sigma ** 2 for d, f in part_f.items()})
    part_values = {d: [_cents(v) for v in values] for d, values in reconciled.items()}
    if part_values:
        company_values = [sum((v[h] for v in part_values.values()), Decimal(0)) for h in range(horizon)]
    else:
        company_values = [p.value for p in company_f.points]
    base = baseline.baseline(subscriptions, months)
    zero = [Decimal(0)] * horizon
    return {
        "currency": currency, "horizon": horizon, "generatedBy": VERSION,
        "company": _series(COMPANY, None, company_history, company_f, company_values, months, base.get("", zero)),
        "departments": [
            _series(names.get(d, NO_DEPARTMENT) if d else NO_DEPARTMENT, d or None, part_histories[d], part_f[d],
                    part_values[d], months, base.get(d, zero))
            for d in sorted(part_f, key=lambda k: (k == "", names.get(k, k)))],
        "savings": [{"month": _month(s.month), "expected": s.expected, "confirmed": s.confirmed}
                    for s in savings.savings(findings, months, today)],
    }


def _tsv(clickhouse: httpx.Client, query: str, params: dict, settings: dict | None = None) -> list[list[str]]:
    r = clickhouse.post("/", params={**{f"param_{k}": v for k, v in params.items()}, **(settings or {})},
                        content=query.encode())
    r.raise_for_status()
    return [line.split("\t") for line in r.text.splitlines() if line]


def fetch_spend(clickhouse: httpx.Client, tenant: str, currency: str, months: int, tenant_setting: bool = False
                ) -> tuple[list[tuple[date, Decimal]], dict[str, list[tuple[date, Decimal]]]]:
    """With tenant_setting every query names the company in SQL_tenant_id, as the row policies of a reader ask
    (ADR 0035); the WHERE still says it too."""
    window = ("tenant_id = {tenant:UUID} AND currency = {currency:String} AND month < toStartOfMonth(today()) "
              "AND month >= addMonths(toStartOfMonth(today()), -{back:UInt32})")
    params = {"tenant": tenant, "currency": currency, "back": months}
    settings = {"SQL_tenant_id": tenant} if tenant_setting else None
    company = [(date.fromisoformat(m), Decimal(v)) for m, v in _tsv(
        clickhouse, f"SELECT toString(month), toString(sum(billed)) FROM spend_by_month WHERE {window} "
                    "GROUP BY month ORDER BY month FORMAT TSV", params, settings)]
    departments: dict[str, list[tuple[date, Decimal]]] = {}
    for d, m, v in _tsv(clickhouse, "SELECT department, toString(month), toString(sum(billed)) "
                                    f"FROM spend_by_department_month WHERE {window} "
                                    "GROUP BY department, month ORDER BY department, month FORMAT TSV", params,
                            settings):
        departments.setdefault(d, []).append((date.fromisoformat(m), Decimal(v)))
    return company, departments


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="costguard-forecast")
    parser.add_argument("--tenant", required=True, help="tenant id: whose spend to read from ClickHouse")
    parser.add_argument("--months", type=int, default=24, help="months of history")
    parser.add_argument("--horizon", type=int, default=6)
    args = parser.parse_args(argv)
    core = httpx.Client(base_url=os.environ["CORE_URL"], timeout=60,
                        headers={"Authorization": f"Bearer {os.environ['CORE_TOKEN']}"})
    clickhouse = httpx.Client(base_url=os.environ["CLICKHOUSE_URL"], timeout=60,
                              auth=(os.environ.get("CLICKHOUSE_USER", "default"),
                                    os.environ.get("CLICKHOUSE_PASSWORD", "")))

    def get(path: str):
        r = core.get(path)
        r.raise_for_status()
        return r.json()

    currency = get("/api/v1/company").get("currency", "RUB")
    company, departments = fetch_spend(clickhouse, args.tenant, currency, args.months,
                                       os.environ.get("CLICKHOUSE_TENANT_SETTING") == "1")
    names = {d["id"]: d["name"] for d in get("/api/v1/departments")}
    subscriptions = [baseline.Subscription.of(s) for s in get("/api/v1/subscriptions")]
    body = document(currency, company, departments, names, subscriptions, get("/api/v1/findings"), date.today(),
                    args.horizon)
    r = core.put("/api/v1/analytics/forecast", content=_json(body), headers={"Content-Type": "application/json"})
    r.raise_for_status()
    print(f"forecast {r.json()['id']}: company by {body['company']['model']}, {len(body['departments'])} departments")


def _json(body: dict) -> bytes:
    import json

    return json.dumps(body, ensure_ascii=False, default=lambda v: float(v) if isinstance(v, Decimal) else str(v)
                      ).encode()


if __name__ == "__main__":
    main()
