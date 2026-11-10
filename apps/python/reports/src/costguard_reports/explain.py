"""A few sentences on a report for the people who read it: what was spent, where it goes, what can be saved (ADR 0044).

The text is written by a language model through the CostGuard gateway (ADR 0013) when one is set up, from the facts
of the report only. A model can get a number wrong, so every number in its answer is checked against the facts; an
answer with a number that is not there, or no answer, gives way to the template, which says the same plainly. The
report is never held up by the model.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from decimal import Decimal

from costguard_common import llm
from costguard_reports.report import METHODS, Report, label

SYSTEM = (
    "Ты финансовый аналитик. Напиши 3-5 коротких предложений по-русски для руководителя компании: сколько потрачено "
    "за период и как менялись расходы, что ожидается дальше по прогнозу и насколько он надёжен, сколько можно "
    "сэкономить по открытым находкам. Используй только числа из фактов, в том же виде, как они записаны; не "
    "придумывай причин, которых нет в фактах. Без заголовков, списков и markdown."
)
TASK_GOAL = "Пояснение к отчёту о расходах для руководителя"
_NUMBER = re.compile(r"\d[\d\s ]*(?:[.,]\d+)?")


@dataclass(frozen=True)
class Explanation:
    text: str
    source: str  # "model" or "template"


def _money(v: Decimal | float) -> str:
    return f"{Decimal(str(v)).quantize(Decimal(1)):,}".replace(",", " ")


def facts(r: Report) -> dict:
    """What the explanation may say, numbers already written the way the text should show them."""
    out: dict = {"компания": r.company, "валюта": r.currency, "расходы за период": _money(r.total),
                 "месяцев": len(r.months), "открытых находок": r.open_findings,
                 "ожидаемая экономия": _money(r.expected_savings),
                 "подтверждённая экономия": _money(r.confirmed_savings)}
    if r.months:
        first, last = r.months[0], r.months[-1]
        out["первый месяц"] = {"месяц": label(first[0]), "расходы": _money(first[1])}
        out["последний месяц"] = {"месяц": label(last[0]), "расходы": _money(last[1])}
        if first[1] > 0 and len(r.months) > 1:
            out["изменение, %"] = int(round((last[1] - first[1]) / first[1] * 100))
    if r.forecast.points:
        months = r.future_months()
        out["прогноз"] = [{"месяц": label(m), "расходы": _money(p.value), "от": _money(p.low), "до": _money(p.high)}
                          for m, p in zip(months, r.forecast.points, strict=True)]
        out["метод прогноза"] = METHODS[r.forecast.method]
    if r.forecast.champion:
        out["ошибка прогноза на истории, %"] = int(round(r.forecast.champion.wape * 100))
    return out


def _numbers(text: str) -> set[str]:
    """The numbers of a text with spaces and the decimal comma made one way, so "1 200,5" and "1200.5" match."""
    out = set()
    for m in _NUMBER.finditer(text):
        n = re.sub(r"[\s ]", "", m.group()).replace(",", ".").rstrip(".")
        if n:
            out.add(n.lstrip("0") or "0")
    return out


def grounded(answer: str, known: dict) -> bool:
    """Every number of the answer is in the facts. Years and month counts are facts too, so they pass."""
    allowed = _numbers(json.dumps(known, ensure_ascii=False))
    return _numbers(answer) <= allowed


def template(r: Report) -> str:
    if not r.months:
        return "Расходов за период пока нет."
    f = facts(r)
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


def explain(r: Report, model: llm.Llm | None, task_id: str | None = None) -> Explanation:
    if model is not None and r.months:
        known = facts(r)
        try:
            answer = model.complete(SYSTEM, json.dumps(known, ensure_ascii=False, indent=1), task_id).strip()
        except llm.LlmRefused:
            answer = ""
        if answer and len(answer) <= 1500 and grounded(answer, known):
            return Explanation(answer, "model")
    return Explanation(template(r), "template")
