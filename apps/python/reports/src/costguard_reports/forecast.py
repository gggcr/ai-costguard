"""Monthly spend forecast: Holt's linear exponential smoothing (level and trend).

alpha and beta are chosen on a grid by the smallest one-step-ahead squared error over the history. The interval
is ±1.96 standard deviations of those errors, widening with the horizon as sqrt(h). With fewer than MIN_MONTHS
months there is no trend to trust: the forecast is the mean of what is there, flat, and says so.

Spend cannot be negative, so forecasts and lower bounds stop at zero.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal

MIN_MONTHS = 4
_GRID = [x / 10 for x in range(1, 10)]


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
    alpha: float | None = None
    beta: float | None = None


def _money(x: float) -> Decimal:
    return Decimal(str(max(x, 0.0))).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)


def _errors(series: list[float], alpha: float, beta: float) -> tuple[float, float, list[float]]:
    level, trend = series[0], series[1] - series[0]
    errors = []
    for x in series[1:]:
        predicted = level + trend
        errors.append(x - predicted)
        new_level = alpha * x + (1 - alpha) * predicted
        trend = beta * (new_level - level) + (1 - beta) * trend
        level = new_level
    return level, trend, errors


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
                                 for h in range(1, horizon + 1)])
    best = None
    for alpha in _GRID:
        for beta in _GRID:
            level, trend, errors = _errors(series, alpha, beta)
            sse = sum(e * e for e in errors)
            if best is None or sse < best[0]:
                best = (sse, alpha, beta, level, trend, errors)
    _, alpha, beta, level, trend, errors = best
    sigma = math.sqrt(sum(e * e for e in errors) / len(errors))
    points = []
    for h in range(1, horizon + 1):
        value = level + h * trend
        width = 1.96 * sigma * math.sqrt(h)
        points.append(Point(h, _money(value), _money(value - width), _money(value + width)))
    return Forecast("holt", points, alpha, beta)
