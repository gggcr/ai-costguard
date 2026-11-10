"""Monthly spend forecast by the model that forecast this series best (ADR 0034).

With MIN_MONTHS to backtest.MIN_MONTHS months there is too little to test models on, and Holt's level and trend
forecasts, as before. From backtest.MIN_MONTHS months every model of models.MODELS is backtested and the champion
forecasts; the challenger is kept beside it, so a report can say what came second and by how much. With fewer than
MIN_MONTHS months there is no trend to trust: the forecast is the mean of what is there, flat, and says so.

The interval is ±1.96 standard deviations of the champion's one-step errors, widening with the horizon as sqrt(h).
Spend cannot be negative, so forecasts and lower bounds stop at zero.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from decimal import ROUND_HALF_EVEN, Decimal

from costguard_reports.backtest import Score, backtest
from costguard_reports.models import MODELS, holt

MIN_MONTHS = 4


@dataclass(frozen=True)
class Point:
    step: int
    value: Decimal
    low: Decimal
    high: Decimal


@dataclass(frozen=True)
class Forecast:
    method: str
    points: list[Point]
    scores: list[Score] = field(default_factory=list)
    sigma: float = 0.0  # of the one-step errors: the reconciliation weighs series by it

    @property
    def champion(self) -> Score | None:
        return next((s for s in self.scores if s.model == self.method), None)

    @property
    def challenger(self) -> Score | None:
        return next((s for s in self.scores if s.model != self.method), None)


def _money(x: float) -> Decimal:
    return Decimal(str(max(x, 0.0))).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)


def sigma(errors: list[float]) -> float:
    return math.sqrt(sum(e * e for e in errors) / len(errors)) if errors else 0.0


def forecast(history: list[Decimal], horizon: int = 3) -> Forecast:
    if horizon < 1:
        raise ValueError("horizon must be at least 1")
    series = [float(x) for x in history]
    if not series:
        return Forecast("empty", [Point(h, Decimal("0.00"), Decimal("0.00"), Decimal("0.00"))
                                  for h in range(1, horizon + 1)])
    if len(series) < MIN_MONTHS:
        mean = sum(series) / len(series)
        spread = max(series) - min(series)
        return Forecast("mean", [Point(h, _money(mean), _money(mean - spread), _money(mean + spread))
                                 for h in range(1, horizon + 1)], sigma=spread / 1.96)
    scores = backtest(series, horizon)
    fit = MODELS[scores[0].model](series, horizon) if scores else holt(series, horizon)
    s = sigma(fit.errors)
    points = []
    for h, value in enumerate(fit.points, start=1):
        width = 1.96 * s * math.sqrt(h)
        points.append(Point(h, _money(value), _money(value - width), _money(value + width)))
    return Forecast(fit.name, points, scores, s)
