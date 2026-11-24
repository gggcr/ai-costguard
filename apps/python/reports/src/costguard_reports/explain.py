"""A few sentences on a report for the people who read it: what was spent, where it goes, what can be saved (ADR 0044).

The text is written by a language model through the CostGuard gateway (ADR 0013) when one is set up, from the facts
of the report only. A model can get a number wrong, so every number in its answer is checked against the facts; an
answer with a number that is not there, or no answer, gives way to the template, which says the same plainly. The
report is never held up by the model. Everything is in the company's language (ADR 0074): Russian, or English with
English month names and 12,345 for numbers.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from decimal import Decimal

from costguard_common import llm
from costguard_reports.report import Report, _lang, _method, label

SYSTEM = (
    "Ты финансовый аналитик. Напиши 3-5 коротких предложений по-русски для руководителя компании: сколько потрачено "
    "за период и как менялись расходы, что ожидается дальше по прогнозу и насколько он надёжен, сколько можно "
    "сэкономить по открытым находкам. Используй только числа из фактов, в том же виде, как они записаны; не "
    "придумывай причин, которых нет в фактах. Без заголовков, списков и markdown."
)
SYSTEM_EN = (
    "You are a financial analyst. Write 3-5 short sentences in English for the company's management: how much was "
    "spent in the period and how spending changed, what the forecast expects next and how reliable it is, how much "
    "the open findings can save. Use only numbers from the facts, written exactly as they are there; do not invent "
    "reasons the facts do not give. No headings, lists or markdown."
)
TASK_GOAL = "Пояснение к отчёту о расходах для руководителя"
TASK_GOAL_EN = "An explanation of the spend report for management"
_NUMBER = re.compile(r"\d[\d\s ]*(?:[.,]\d+)?")
_NUMBER_EN = re.compile(r"\d(?:\d|,\d{3})*(?:\.\d+)?")

# The names of the facts the model reads, per language; the template reads them by the Russian name.
_KEYS_EN = {"компания": "company", "валюта": "currency", "расходы за период": "spend in the period",
            "месяцев": "months", "открытых находок": "open findings", "ожидаемая экономия": "expected savings",
            "подтверждённая экономия": "confirmed savings", "первый месяц": "first month",
            "последний месяц": "last month", "месяц": "month", "расходы": "spend", "изменение, %": "change, %",
            "прогноз": "forecast", "от": "from", "до": "to", "метод прогноза": "forecast method",
            "ошибка прогноза на истории, %": "forecast error on history, %"}


@dataclass(frozen=True)
class Explanation:
    text: str
    source: str  # "model" or "template"


def task_goal(language: str | None) -> str:
    """The goal the gateway's task-fit check sees for the model's call (ADR 0011)."""
    return TASK_GOAL_EN if _lang(language) == "en" else TASK_GOAL


def _money(v: Decimal | float, language: str = "ru") -> str:
    grouped = f"{Decimal(str(v)).quantize(Decimal(1)):,}"
    return grouped if _lang(language) == "en" else grouped.replace(",", " ")


def _facts(r: Report) -> dict:
    """The facts under their Russian names, numbers already written the way the text should show them."""
    lang = r.language
    out: dict = {"компания": r.company, "валюта": r.currency, "расходы за период": _money(r.total, lang),
                 "месяцев": len(r.months), "открытых находок": r.open_findings,
                 "ожидаемая экономия": _money(r.expected_savings, lang),
                 "подтверждённая экономия": _money(r.confirmed_savings, lang)}
    if r.months:
        first, last = r.months[0], r.months[-1]
        out["первый месяц"] = {"месяц": label(first[0], lang), "расходы": _money(first[1], lang)}
        out["последний месяц"] = {"месяц": label(last[0], lang), "расходы": _money(last[1], lang)}
        if first[1] > 0 and len(r.months) > 1:
            out["изменение, %"] = int(round((last[1] - first[1]) / first[1] * 100))
    if r.forecast.points:
        months = r.future_months()
        out["прогноз"] = [{"месяц": label(m, lang), "расходы": _money(p.value, lang), "от": _money(p.low, lang),
                           "до": _money(p.high, lang)}
                          for m, p in zip(months, r.forecast.points, strict=True)]
        out["метод прогноза"] = _method(r, r.forecast.method)
    if r.forecast.champion:
        out["ошибка прогноза на истории, %"] = int(round(r.forecast.champion.wape * 100))
    return out


def _named(value, language: str):
    if _lang(language) != "en":
        return value
    if isinstance(value, dict):
        return {_KEYS_EN[k]: _named(v, language) for k, v in value.items()}
    if isinstance(value, list):
        return [_named(v, language) for v in value]
    return value


def facts(r: Report) -> dict:
    """What the explanation may say, in the company's language."""
    return _named(_facts(r), r.language)


def _numbers(text: str, language: str = "ru") -> set[str]:
    """The numbers of a text made one way, so "1 200,5" and "1200.5" match, and in English "1,200.5" too."""
    out = set()
    if _lang(language) == "en":
        for m in _NUMBER_EN.finditer(text):
            n = m.group().replace(",", "")
            out.add(n.lstrip("0") or "0")
        return out
    for m in _NUMBER.finditer(text):
        n = re.sub(r"[\s ]", "", m.group()).replace(",", ".").rstrip(".")
        if n:
            out.add(n.lstrip("0") or "0")
    return out


def grounded(answer: str, known: dict, language: str = "ru") -> bool:
    """Every number of the answer is in the facts. Years and month counts are facts too, so they pass."""
    allowed = _numbers(json.dumps(known, ensure_ascii=False), language)
    return _numbers(answer, language) <= allowed


def template(r: Report) -> str:
    if _lang(r.language) == "en":
        return _template_en(r)
    if not r.months:
        return "Расходов за период пока нет."
    f = _facts(r)
    text = f"За {f['месяцев']} мес. потрачено {f['расходы за период']} {r.currency}."
    if "изменение, %" in f:
        change = f["изменение, %"]
        if change == 0:
            text += f" Расходы в {f['последний месяц']['месяц']} — на уровне {f['первый месяц']['месяц']}."
        else:
            text += (f" С {f['первый месяц']['месяц']} по {f['последний месяц']['месяц']} расходы "
                     f"{'выросли' if change > 0 else 'снизились'} на {abs(change)}%.")
    if f.get("прогноз"):
        nxt = f["прогноз"][0]
        text += (f" В {nxt['месяц']} ожидается {nxt['расходы']} {r.currency} (от {nxt['от']} до {nxt['до']}),"
                 f" прогноз — {f['метод прогноза']}")
        text += (f", ошибка на истории {f['ошибка прогноза на истории, %']}%." if "ошибка прогноза на истории, %" in f
                 else ".")
    if r.open_findings:
        text += (f" Открытых находок: {r.open_findings}, по ним можно сэкономить {f['ожидаемая экономия']}"
                 f" {r.currency}.")
    if r.confirmed_savings > 0:
        text += f" Уже подтверждено экономии: {f['подтверждённая экономия']} {r.currency}."
    return text


def _template_en(r: Report) -> str:
    if not r.months:
        return "No spend in the period yet."
    f = _facts(r)
    months = f["месяцев"]
    text = f"{f['расходы за период']} {r.currency} was spent in {months} month{'s' if months != 1 else ''}."
    if "изменение, %" in f:
        change = f["изменение, %"]
        if change == 0:
            text += f" Spend in {f['последний месяц']['месяц']} is level with {f['первый месяц']['месяц']}."
        else:
            text += (f" From {f['первый месяц']['месяц']} to {f['последний месяц']['месяц']} spend "
                     f"{'rose' if change > 0 else 'fell'} by {abs(change)}%.")
    if f.get("прогноз"):
        nxt = f["прогноз"][0]
        text += (f" {nxt['месяц']} is expected at {nxt['расходы']} {r.currency} (from {nxt['от']} to {nxt['до']}),"
                 f" forecast by {f['метод прогноза']}")
        text += (f", error on history {f['ошибка прогноза на истории, %']}%." if "ошибка прогноза на истории, %" in f
                 else ".")
    if r.open_findings:
        text += (f" Open findings: {r.open_findings}, which can save {f['ожидаемая экономия']} {r.currency}.")
    if r.confirmed_savings > 0:
        text += f" Savings already confirmed: {f['подтверждённая экономия']} {r.currency}."
    return text


def explain(r: Report, model: llm.Llm | None, task_id: str | None = None) -> Explanation:
    if model is not None and r.months:
        known = facts(r)
        system = SYSTEM_EN if _lang(r.language) == "en" else SYSTEM
        try:
            answer = model.complete(system, json.dumps(known, ensure_ascii=False, indent=1), task_id).strip()
        except llm.LlmRefused:
            answer = ""
        if answer and len(answer) <= 1500 and grounded(answer, known, r.language):
            return Explanation(answer, "model")
    return Explanation(template(r), "template")
