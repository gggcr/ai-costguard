"""What the registry says the company will pay: subscriptions × seats × prices, month by month (ADR 0034).

The statistical forecast follows the history; this follows the contracts. A monthly subscription costs its seats
times the price of a seat every month. A yearly one is paid at once in its renewal month, and again twelve months
later. The price of a seat is per billing period, as the audit rules read it. Departments are kept apart, so the
baseline of a department sits beside its statistical forecast.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from decimal import Decimal


@dataclass(frozen=True)
class Subscription:
    id: str
    department_id: str
    seats: int
    price_per_seat: Decimal
    yearly: bool
    renewal: date

    @staticmethod
    def of(api: dict) -> Subscription:
        """From GET /api/v1/subscriptions of the core."""
        return Subscription(api["id"], api["departmentId"], int(api["seats"]), Decimal(str(api["pricePerSeat"])),
                            api["billingPeriod"] == "year", date.fromisoformat(api["renewalDate"][:10]))

    def charge(self, month: date) -> Decimal:
        cost = self.price_per_seat * self.seats
        if not self.yearly:
            return cost
        return cost if month.month == self.renewal.month and month >= self.renewal.replace(day=1) else Decimal(0)


def months_after(last: date, horizon: int) -> list[date]:
    out, y, m = [], last.year, last.month
    for _ in range(horizon):
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
        out.append(date(y, m, 1))
    return out


def baseline(subscriptions: list[Subscription], months: list[date]) -> dict[str, list[Decimal]]:
    """Department id -> the charge of every month; the key "" is the whole company."""
    out: dict[str, list[Decimal]] = defaultdict(lambda: [Decimal(0)] * len(months))
    for s in subscriptions:
        for i, month in enumerate(months):
            charge = s.charge(month)
            out[s.department_id][i] += charge
            out[""][i] += charge
    if "" not in out:
        out[""] = [Decimal(0)] * len(months)
    return dict(out)
