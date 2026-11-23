"""The monthly spend report: history, forecast and findings, as XLSX or PDF, in the company's language (ADR 0074:
"ru" or "en").

The PDF uses the bundled DejaVu Sans (fonts/LICENSE), because the standard PDF fonts have no Cyrillic.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path

from costguard_reports.forecast import Forecast, forecast

FONT = Path(__file__).parent / "fonts" / "DejaVuSans.ttf"
METHODS = {"holt": "метод Хольта (уровень и тренд)", "ets_damped": "ETS с затухающим трендом",
           "theta": "метод Theta", "naive": "повтор последнего месяца", "seasonal_naive": "тот же месяц год назад",
           "mean": "среднее", "empty": "нет данных"}
METHODS_EN = {"holt": "Holt's method (level and trend)", "ets_damped": "ETS with a damped trend",
              "theta": "the Theta method", "naive": "the last month repeated",
              "seasonal_naive": "the same month a year ago", "mean": "the mean", "empty": "no data"}

MONTHS_RU = ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]
MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

TEXT = {
    "ru": {"sheet": "Расходы", "title": "Расходы {company}", "made": "Сформирован {day}, валюта {currency}",
           "month": "Месяц", "actual": "Факт", "forecast": "Прогноз", "low": "Нижняя граница",
           "high": "Верхняя граница",
           "low_short": "Нижняя", "high_short": "Верхняя", "chart": "Факт и прогноз", "summary": "Итоги",
           "metric": "Показатель", "value": "Значение", "total": "Расходы за период", "open": "Открытых находок",
           "expected": "Ожидаемая экономия", "confirmed": "Подтверждённая экономия", "method": "Метод прогноза",
           "wape": "Ошибка прогноза на истории (WAPE)", "next": "Следующая модель", "next_wape": "Её ошибка (WAPE)",
           "explanation": "Пояснение", "none": "Расходов пока нет.",
           "short": "Истории меньше четырёх месяцев: прогноз равен среднему.",
           "note": "Прогноз: {method}, интервал 95%.",
           "chosen": " Модель выбрана проверкой на истории: ошибка {wape:.0%} от суммы расходов (WAPE),"
                     " MASE {mase:.2f}.",
           "runner": " Следующая — {method}, ошибка {wape:.0%}.", "company": "компании"},
    "en": {"sheet": "Spend", "title": "Spend of {company}", "made": "Generated {day}, currency {currency}",
           "month": "Month", "actual": "Actual", "forecast": "Forecast", "low": "Lower bound", "high": "Upper bound",
           "low_short": "Lower", "high_short": "Upper", "chart": "Actual and forecast", "summary": "Summary",
           "metric": "Metric", "value": "Value", "total": "Spend in the period", "open": "Open findings",
           "expected": "Expected savings", "confirmed": "Confirmed savings", "method": "Forecast method",
           "wape": "Forecast error on history (WAPE)", "next": "Runner-up model", "next_wape": "Its error (WAPE)",
           "explanation": "Explanation", "none": "No spend yet.",
           "short": "Less than four months of history: the forecast is the mean.",
           "note": "Forecast: {method}, 95% interval.",
           "chosen": " The model was chosen by testing on history: error {wape:.0%} of spend (WAPE), MASE {mase:.2f}.",
           "runner": " Runner-up: {method}, error {wape:.0%}.", "company": "the company"},
}


def _lang(language: str | None) -> str:
    return "en" if (language or "").lower() == "en" else "ru"


@dataclass(frozen=True)
class Report:
    company: str
    currency: str
    months: list[tuple[date, Decimal]]
    forecast: Forecast
    open_findings: int
    expected_savings: Decimal
    confirmed_savings: Decimal
    generated: date
    language: str = "ru"

    @property
    def t(self) -> dict[str, str]:
        return TEXT[_lang(self.language)]

    @property
    def total(self) -> Decimal:
        return sum((v for _, v in self.months), Decimal(0))

    def future_months(self) -> list[date]:
        last = self.months[-1][0] if self.months else self.generated.replace(day=1)
        out = []
        y, m = last.year, last.month
        for _ in self.forecast.points:
            y, m = (y + 1, 1) if m == 12 else (y, m + 1)
            out.append(date(y, m, 1))
        return out


def build(company: str, currency: str, months: list[tuple[date, Decimal]], findings: dict, generated: date,
          horizon: int = 3, language: str = "ru") -> Report:
    months = sorted(months)
    return Report(company, currency, months, forecast([v for _, v in months], horizon),
                  int(findings.get("open", 0)), Decimal(str(findings.get("expectedSavings", 0))),
                  Decimal(str(findings.get("confirmedSavings", 0))), generated, _lang(language))


def label(d: date, language: str = "ru") -> str:
    names = MONTHS_EN if _lang(language) == "en" else MONTHS_RU
    return f"{names[d.month - 1]} {d.year}"


def _day(r: Report) -> str:
    return f"{r.generated:%b %d, %Y}" if r.language == "en" else f"{r.generated:%d.%m.%Y}"


def _method(r: Report, name: str) -> str:
    return (METHODS_EN if r.language == "en" else METHODS)[name]


def xlsx(r: Report, explanation: str | None = None) -> bytes:
    """explanation: the text of explain.explain, on the summary sheet."""
    from openpyxl import Workbook
    from openpyxl.chart import LineChart, Reference
    from openpyxl.styles import Font

    book = Workbook()
    sheet = book.active
    t = r.t
    sheet.title = t["sheet"]
    sheet.append([t["title"].format(company=r.company), None, None, None])
    sheet["A1"].font = Font(bold=True, size=14)
    sheet.append([t["made"].format(day=_day(r), currency=r.currency)])
    sheet.append([])
    sheet.append([t["month"], t["actual"], t["forecast"], t["low"], t["high"]])
    for cell in sheet[4]:
        cell.font = Font(bold=True)
    first = sheet.max_row + 1
    for d, v in r.months:
        sheet.append([label(d, r.language), float(v), None, None, None])
    for d, p in zip(r.future_months(), r.forecast.points, strict=True):
        sheet.append([label(d, r.language), None, float(p.value), float(p.low), float(p.high)])
    last = sheet.max_row
    for row in sheet.iter_rows(min_row=first, max_row=last, min_col=2, max_col=5):
        for cell in row:
            cell.number_format = "#,##0.00"
    sheet.column_dimensions["A"].width = 12
    for col in "BCDE":
        sheet.column_dimensions[col].width = 18

    chart = LineChart()
    chart.title = t["chart"]
    chart.add_data(Reference(sheet, min_col=2, max_col=3, min_row=4, max_row=last), titles_from_data=True)
    chart.set_categories(Reference(sheet, min_col=1, min_row=first, max_row=last))
    chart.height, chart.width = 8, 18
    sheet.add_chart(chart, "G4")

    summary = book.create_sheet(t["summary"])
    summary.append([t["metric"], t["value"]])
    summary.append([t["total"], float(r.total)])
    summary.append([t["open"], r.open_findings])
    summary.append([t["expected"], float(r.expected_savings)])
    summary.append([t["confirmed"], float(r.confirmed_savings)])
    summary.append([t["method"], _method(r, r.forecast.method)])
    if r.forecast.champion:
        summary.append([t["wape"], r.forecast.champion.wape])
    if r.forecast.challenger:
        summary.append([t["next"], _method(r, r.forecast.challenger.model)])
        summary.append([t["next_wape"], r.forecast.challenger.wape])
    summary.column_dimensions["A"].width = 28
    summary.column_dimensions["B"].width = 18
    if explanation:
        from openpyxl.styles import Alignment

        summary.append([])
        summary.append([t["explanation"], explanation])
        summary.cell(summary.max_row, 2).alignment = Alignment(wrap_text=True, vertical="top")
        summary.row_dimensions[summary.max_row].height = 15 * (len(explanation) // 60 + 1)
        summary.column_dimensions["B"].width = 60
    out = io.BytesIO()
    book.save(out)
    return out.getvalue()


def _money(v: Decimal, language: str = "ru") -> str:
    return f"{v:,.2f}" if language == "en" else f"{v:,.2f}".replace(",", " ")


def pdf(r: Report, explanation: str | None = None) -> bytes:
    from fpdf import FPDF

    doc = FPDF()
    doc.add_font("DejaVu", "", str(FONT))
    doc.set_font("DejaVu", size=11)
    doc.add_page()
    doc.set_font_size(16)
    t, lang = r.t, r.language
    doc.cell(0, 10, t["title"].format(company=r.company), new_x="LMARGIN", new_y="NEXT")
    doc.set_font_size(10)
    doc.cell(0, 6, t["made"].format(day=_day(r), currency=r.currency), new_x="LMARGIN", new_y="NEXT")
    doc.ln(4)
    doc.set_font_size(11)
    for name, value in [(t["total"], _money(r.total, lang)), (t["open"], str(r.open_findings)),
                        (t["expected"], _money(r.expected_savings, lang)),
                        (t["confirmed"], _money(r.confirmed_savings, lang))]:
        doc.cell(80, 7, name)
        doc.cell(0, 7, value, new_x="LMARGIN", new_y="NEXT")
    if explanation:
        doc.ln(3)
        doc.multi_cell(0, 6, explanation)
    doc.ln(4)
    widths = (35, 38, 38, 38, 38)
    for w, title in zip(widths, (t["month"], t["actual"], t["forecast"], t["low_short"], t["high_short"]),
                        strict=True):
        doc.cell(w, 7, title, border=1)
    doc.ln()
    rows = [(label(d, lang), _money(v, lang), "", "", "") for d, v in r.months]
    rows += [(label(d, lang), "", _money(p.value, lang), _money(p.low, lang), _money(p.high, lang))
             for d, p in zip(r.future_months(), r.forecast.points, strict=True)]
    for row in rows:
        for w, text in zip(widths, row, strict=True):
            doc.cell(w, 7, text, border=1, align="R" if text and text[0].isdigit() else "L")
        doc.ln()
    doc.ln(3)
    doc.set_font_size(9)
    doc.multi_cell(0, 5, note(r.forecast, lang))
    return bytes(doc.output())


def note(f: Forecast, language: str = "ru") -> str:
    lang = _lang(language)
    t, methods = TEXT[lang], METHODS_EN if lang == "en" else METHODS
    if f.method == "empty":
        return t["none"]
    if f.method == "mean":
        return t["short"]
    text = t["note"].format(method=methods[f.method])
    if f.champion:
        text += t["chosen"].format(wape=f.champion.wape, mase=f.champion.mase)
    if f.challenger:
        text += t["runner"].format(method=methods[f.challenger.model], wape=f.challenger.wape)
    return text
