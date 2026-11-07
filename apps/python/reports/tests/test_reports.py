import io
from datetime import date
from decimal import Decimal

import httpx
import pytest
from openpyxl import load_workbook

from costguard_reports import cli, report
from costguard_reports.forecast import forecast


def months(values, start=(2026, 1)):
    y, m = start
    out = []
    for v in values:
        out.append((date(y, m, 1), Decimal(v)))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


def test_linear_growth_is_followed():
    f = forecast([Decimal(100 + 10 * i) for i in range(12)], horizon=3)
    assert f.method == "holt"
    assert [p.value for p in f.points] == [Decimal("220.00"), Decimal("230.00"), Decimal("240.00")]
    assert all(p.low <= p.value <= p.high for p in f.points)


def test_interval_widens_with_horizon():
    noisy = [Decimal(v) for v in [100, 130, 90, 120, 110, 140, 95, 125, 115, 135]]
    f = forecast(noisy, horizon=4)
    widths = [p.high - p.low for p in f.points]
    assert widths == sorted(widths) and widths[0] > 0


def test_never_negative():
    f = forecast([Decimal(v) for v in [500, 400, 300, 200, 100, 50]], horizon=6)
    assert all(p.value >= 0 and p.low >= 0 for p in f.points)


def test_short_history_falls_back_to_the_mean():
    f = forecast([Decimal(100), Decimal(200)], horizon=2)
    assert f.method == "mean" and f.points[0].value == Decimal("150.00")
    assert forecast([], horizon=1).method == "empty"
    with pytest.raises(ValueError):
        forecast([Decimal(1)], horizon=0)


FINDINGS = {"open": 3, "expectedSavings": 12000.5, "confirmedSavings": 3000}


def sample():
    return report.build("ООО «Ромашка»", "RUB", months([1000 + 50 * i for i in range(11)], (2025, 12)), FINDINGS,
                        date(2026, 11, 7))


def test_future_months_cross_the_year():
    r = sample()
    assert r.months[-1][0] == date(2026, 10, 1)
    assert r.future_months() == [date(2026, 11, 1), date(2026, 12, 1), date(2027, 1, 1)]


def test_xlsx():
    book = load_workbook(io.BytesIO(report.xlsx(sample())))
    sheet = book["Расходы"]
    assert sheet["A1"].value == "Расходы ООО «Ромашка»"
    assert sheet["A5"].value == "дек 2025" and sheet["B5"].value == 1000
    assert sheet["A16"].value == "ноя 2026" and sheet["C16"].value > 1500
    assert len(sheet._charts) == 1
    assert book["Итоги"]["B3"].value == 3


def test_pdf_has_cyrillic_text():
    from pypdf import PdfReader

    content = report.pdf(sample())
    assert content.startswith(b"%PDF")
    text = PdfReader(io.BytesIO(content)).pages[0].extract_text()
    assert "Ромашка" in text and "Прогноз" in text and "ноя 2026" in text


def test_pdf_with_no_spend():
    r = report.build("X", "RUB", [], {}, date(2026, 11, 7))
    assert report.pdf(r).startswith(b"%PDF")


def test_fetch_from_the_core():
    def handle(request):
        if request.url.path.endswith("/summary"):
            assert request.url.params["months"] == "6"
            return httpx.Response(200, json={"agentSpend": [{"month": "2026-10", "amount": 120.5}],
                                             "findings": FINDINGS})
        return httpx.Response(200, json={"name": "Ромашка", "currency": "RUB"})

    core = httpx.Client(base_url="http://core", transport=httpx.MockTransport(handle))
    company, currency, series, findings = cli.fetch(core, 6)
    assert company == "Ромашка" and series == [(date(2026, 10, 1), Decimal("120.5"))] and findings["open"] == 3


def test_fetch_from_clickhouse():
    def handle(request):
        assert request.url.params["param_tenant"] == "t-1" and request.url.params["param_back"] == "11"
        assert b"spend_by_month" in request.content
        return httpx.Response(200, text="2026-09-01\t100.5\n2026-10-01\t200\n")

    ch = httpx.Client(base_url="http://ch", transport=httpx.MockTransport(handle))
    assert cli.fetch_clickhouse(ch, "t-1", "RUB", 12) == [(date(2026, 9, 1), Decimal("100.5")),
                                                          (date(2026, 10, 1), Decimal("200"))]
