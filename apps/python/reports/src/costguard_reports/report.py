"""The monthly spend report: history, forecast and findings, as XLSX or PDF.

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
MONTHS_RU = ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]


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
          horizon: int = 3) -> Report:
    months = sorted(months)
    return Report(company, currency, months, forecast([v for _, v in months], horizon),
                  int(findings.get("open", 0)), Decimal(str(findings.get("expectedSavings", 0))),
                  Decimal(str(findings.get("confirmedSavings", 0))), generated)


def label(d: date) -> str:
    return f"{MONTHS_RU[d.month - 1]} {d.year}"


def xlsx(r: Report) -> bytes:
    from openpyxl import Workbook
    from openpyxl.chart import LineChart, Reference
    from openpyxl.styles import Font

    book = Workbook()
    sheet = book.active
    sheet.title = "Расходы"
    sheet.append([f"Расходы {r.company}", None, None, None])
    sheet["A1"].font = Font(bold=True, size=14)
    sheet.append([f"Сформирован {r.generated:%d.%m.%Y}, валюта {r.currency}"])
    sheet.append([])
    sheet.append(["Месяц", "Факт", "Прогноз", "Нижняя граница", "Верхняя граница"])
    for cell in sheet[4]:
        cell.font = Font(bold=True)
    first = sheet.max_row + 1
    for d, v in r.months:
        sheet.append([label(d), float(v), None, None, None])
    for d, p in zip(r.future_months(), r.forecast.points, strict=True):
        sheet.append([label(d), None, float(p.value), float(p.low), float(p.high)])
    last = sheet.max_row
    for row in sheet.iter_rows(min_row=first, max_row=last, min_col=2, max_col=5):
        for cell in row:
            cell.number_format = "#,##0.00"
    sheet.column_dimensions["A"].width = 12
    for col in "BCDE":
        sheet.column_dimensions[col].width = 18

    chart = LineChart()
    chart.title = "Факт и прогноз"
    chart.add_data(Reference(sheet, min_col=2, max_col=3, min_row=4, max_row=last), titles_from_data=True)
    chart.set_categories(Reference(sheet, min_col=1, min_row=first, max_row=last))
    chart.height, chart.width = 8, 18
    sheet.add_chart(chart, "G4")

    summary = book.create_sheet("Итоги")
    summary.append(["Показатель", "Значение"])
    summary.append(["Расходы за период", float(r.total)])
    summary.append(["Открытых находок", r.open_findings])
    summary.append(["Ожидаемая экономия", float(r.expected_savings)])
    summary.append(["Подтверждённая экономия", float(r.confirmed_savings)])
    summary.append(["Метод прогноза", METHODS[r.forecast.method]])
    if r.forecast.champion:
        summary.append(["Ошибка прогноза на истории (WAPE)", r.forecast.champion.wape])
    if r.forecast.challenger:
        summary.append(["Следующая модель", METHODS[r.forecast.challenger.model]])
        summary.append(["Её ошибка (WAPE)", r.forecast.challenger.wape])
    summary.column_dimensions["A"].width = 28
    summary.column_dimensions["B"].width = 18
    out = io.BytesIO()
    book.save(out)
    return out.getvalue()


def _money(v: Decimal) -> str:
    return f"{v:,.2f}".replace(",", " ")


def pdf(r: Report) -> bytes:
    from fpdf import FPDF

    doc = FPDF()
    doc.add_font("DejaVu", "", str(FONT))
    doc.set_font("DejaVu", size=11)
    doc.add_page()
    doc.set_font_size(16)
    doc.cell(0, 10, f"Расходы {r.company}", new_x="LMARGIN", new_y="NEXT")
    doc.set_font_size(10)
    doc.cell(0, 6, f"Сформирован {r.generated:%d.%m.%Y}, валюта {r.currency}", new_x="LMARGIN", new_y="NEXT")
    doc.ln(4)
    doc.set_font_size(11)
    for name, value in [("Расходы за период", _money(r.total)), ("Открытых находок", str(r.open_findings)),
                        ("Ожидаемая экономия", _money(r.expected_savings)),
                        ("Подтверждённая экономия", _money(r.confirmed_savings))]:
        doc.cell(80, 7, name)
        doc.cell(0, 7, value, new_x="LMARGIN", new_y="NEXT")
    doc.ln(4)
    widths = (35, 38, 38, 38, 38)
    for w, title in zip(widths, ("Месяц", "Факт", "Прогноз", "Нижняя", "Верхняя"), strict=True):
        doc.cell(w, 7, title, border=1)
    doc.ln()
    rows = [(label(d), _money(v), "", "", "") for d, v in r.months]
    rows += [(label(d), "", _money(p.value), _money(p.low), _money(p.high))
             for d, p in zip(r.future_months(), r.forecast.points, strict=True)]
    for row in rows:
        for w, text in zip(widths, row, strict=True):
            doc.cell(w, 7, text, border=1, align="R" if text and text[0].isdigit() else "L")
        doc.ln()
    doc.ln(3)
    doc.set_font_size(9)
    doc.multi_cell(0, 5, note(r.forecast))
    return bytes(doc.output())


def note(f: Forecast) -> str:
    if f.method == "empty":
        return "Расходов пока нет."
    if f.method == "mean":
        return "Истории меньше четырёх месяцев: прогноз равен среднему."
    text = f"Прогноз: {METHODS[f.method]}, интервал 95%."
    if f.champion:
        text += (f" Модель выбрана проверкой на истории: ошибка {f.champion.wape:.0%} от суммы расходов"
                 f" (WAPE), MASE {f.champion.mase:.2f}.")
    if f.challenger:
        text += f" Следующая — {METHODS[f.challenger.model]}, ошибка {f.challenger.wape:.0%}."
    return text
