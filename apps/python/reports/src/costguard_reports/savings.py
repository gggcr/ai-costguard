"""Expected savings of the findings, month by month (ADR 0034).

A finding saves its monthly amount from the month its work is likely done, with the chance that it gets done. Both
come from how far it got:

    status       chance  starts after
    new          0.2     3 months
    review       0.4     2 months
    accepted     0.6     2 months
    in_progress  0.8     1 month
    postponed    0.3     the month after its date, at least 1 month
    confirmed    1.0     already saving

Rejected and closed findings save nothing. Equipment (asset.*) and a duplicate invoice save once, not every
month: the amount comes in the starting month only. The forecast of spend stays what it is: the savings are a
separate line, the money the findings could take off it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

CHANCE = {"new": Decimal("0.2"), "review": Decimal("0.4"), "accepted": Decimal("0.6"),
          "in_progress": Decimal("0.8"), "postponed": Decimal("0.3"), "confirmed": Decimal(1)}
LAG = {"new": 3, "review": 2, "accepted": 2, "in_progress": 1, "postponed": 1, "confirmed": 0}


def one_off(rule: str) -> bool:
    return rule.startswith("asset.") or rule == "invoice.duplicate"


@dataclass(frozen=True)
class Month:
    month: date
    expected: Decimal
    confirmed: Decimal


def _months_between(a: date, b: date) -> int:
    return (b.year - a.year) * 12 + b.month - a.month


def savings(findings: list[dict], months: list[date], today: date) -> list[Month]:
    """findings as GET /api/v1/findings returns them; months the first days of the forecast months."""
    expected = [Decimal(0)] * len(months)
    confirmed = [Decimal(0)] * len(months)
    this_month = today.replace(day=1)
    for f in findings:
        status = f.get("status")
        if status not in CHANCE:
            continue
        amount = Decimal(str(f.get("expectedSavings", 0)))
        lag = LAG[status]
        if status == "postponed" and f.get("postponedUntil"):
            until = date.fromisoformat(f["postponedUntil"][:10])
            lag = max(_months_between(this_month, until.replace(day=1)) + 1, 1)
        for i, month in enumerate(months):
            since = _months_between(this_month, month)
            if since < lag or one_off(str(f.get("rule", ""))) and since != max(lag, 1):
                continue
            share = (amount * CHANCE[status]).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
            expected[i] += share
            if status == "confirmed":
                confirmed[i] += share
    return [Month(m, e, c) for m, e, c in zip(months, expected, confirmed, strict=True)]
