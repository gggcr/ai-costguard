import json
from datetime import date
from decimal import Decimal

import httpx

from costguard_reports import baseline, publish


def months(values, start=(2025, 11)):
    y, m = start
    out = []
    for v in values:
        out.append((date(y, m, 1), Decimal(v)))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


IT = "11111111-0000-0000-0000-000000000001"
SALES = "11111111-0000-0000-0000-000000000002"


def sample(horizon=3):
    it = months([1000 + 20 * i for i in range(12)])
    sales = months([500 + (i % 3) * 30 for i in range(12)])
    loose = months([50] * 12)
    company = [(m, a + b + c) for (m, a), (_, b), (_, c) in zip(it, sales, loose, strict=True)]
    company[-1] = (company[-1][0], company[-1][1] + 7)  # the company series is not quite the sum
    subs = [baseline.Subscription("s1", IT, 3, Decimal("1000"), False, date(2027, 1, 1))]
    findings = [{"rule": "license.unused", "status": "in_progress", "expectedSavings": 1000}]
    return publish.document("RUB", company, {IT: it, SALES: sales, "": loose}, {IT: "ИТ", SALES: "Продажи"}, subs,
                            findings, date(2026, 11, 10), horizon)


def test_departments_add_up_to_the_company_to_the_kopeck():
    d = sample()
    assert d["horizon"] == 3 and d["currency"] == "RUB"
    assert [p["month"] for p in d["company"]["points"]] == ["2026-11", "2026-12", "2027-01"]
    for h in range(3):
        parts = sum(s["points"][h]["value"] for s in d["departments"])
        assert parts == d["company"]["points"][h]["value"]
    assert [s["name"] for s in d["departments"]] == ["ИТ", "Продажи", "Без отдела"]
    assert d["departments"][-1]["departmentId"] is None


def test_each_series_names_its_model_and_keeps_its_interval_around_the_reconciled_value():
    d = sample()
    it = d["departments"][0]
    assert it["model"] in {"holt", "ets_damped", "theta", "naive"} and it["wape"] is not None
    assert it["challenger"] and it["challengerWape"] >= it["wape"]
    assert all(p["low"] <= p["value"] <= p["high"] for p in it["points"])
    assert len(it["history"]) == 12 and it["history"][0] == {"month": "2025-11", "amount": Decimal(1020 - 20)}


def test_the_baseline_and_the_savings_ride_along():
    d = sample()
    assert [p["baseline"] for p in d["departments"][0]["points"]] == [Decimal(3000)] * 3
    assert d["departments"][1]["points"][0]["baseline"] == Decimal(0)
    assert d["company"]["points"][0]["baseline"] == Decimal(3000)
    assert [s["expected"] for s in d["savings"]] == [Decimal(0), Decimal("800.00"), Decimal("800.00")]


def test_without_spend_the_forecast_starts_after_the_last_month_that_is_over():
    d = publish.document("RUB", [], {}, {}, [], [], date(2026, 1, 15), 2)
    assert [p["month"] for p in d["company"]["points"]] == ["2026-01", "2026-02"]
    assert d["company"]["model"] == "empty" and d["departments"] == []


def test_spend_comes_from_clickhouse_without_the_month_that_is_not_over():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.params, request.content.decode()))
        if "spend_by_department_month" in request.content.decode():
            return httpx.Response(200, text=f"{IT}\t2026-09-01\t100\n\t2026-09-01\t5\n{IT}\t2026-10-01\t110\n")
        return httpx.Response(200, text="2026-09-01\t105\n2026-10-01\t110\n")

    client = httpx.Client(base_url="http://ch", transport=httpx.MockTransport(handler))
    company, departments = publish.fetch_spend(client, "t-1", "RUB", 24)
    assert company == [(date(2026, 9, 1), Decimal(105)), (date(2026, 10, 1), Decimal(110))]
    assert departments == {IT: [(date(2026, 9, 1), Decimal(100)), (date(2026, 10, 1), Decimal(110))],
                           "": [(date(2026, 9, 1), Decimal(5))]}
    assert all("month < toStartOfMonth(today())" in q for _, q in seen)
    assert seen[0][0]["param_tenant"] == "t-1" and seen[0][0]["param_back"] == "24"


def test_the_document_is_valid_json_for_the_core():
    body = json.loads(publish._json(sample()))
    assert isinstance(body["company"]["points"][0]["value"], float)
