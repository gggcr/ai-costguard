"""Forecasting models of a monthly spend series, in plain Python (ADR 0034).

Each model fits a history and returns the point forecasts for the next months and its one-step in-sample errors,
which set the width of the interval and the weights of the reconciliation. The board names statsforecast (ETS,
Theta); these are the same methods without the dependency:

- naive: the last month repeats;
- seasonal_naive: the same month a year ago, with two years of history;
- holt: level and trend (Holt's linear exponential smoothing);
- ets_damped: additive error, additive damped trend (ETS(A,Ad,N)), the trend fades by phi a month;
- theta: the Theta method of Assimakopoulos and Nikolopoulos as Hyndman and Billah showed it: simple exponential
  smoothing plus half the slope of the linear trend.

Smoothing parameters are chosen on a grid by the smallest one-step squared error.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

GRID = [x / 10 for x in range(1, 10)]
PHI = [0.8, 0.9, 0.98]
SEASON = 12


@dataclass(frozen=True)
class Fit:
    name: str
    points: list[float]
    errors: list[float]


def naive(series: list[float], horizon: int) -> Fit:
    errors = [b - a for a, b in zip(series, series[1:], strict=False)]
    return Fit("naive", [series[-1]] * horizon, errors)


def seasonal_naive(series: list[float], horizon: int) -> Fit | None:
    if len(series) < 2 * SEASON:
        return None
    errors = [series[t] - series[t - SEASON] for t in range(SEASON, len(series))]
    points = [series[len(series) - SEASON + (h - 1) % SEASON] for h in range(1, horizon + 1)]
    return Fit("seasonal_naive", points, errors)


def _damped(series: list[float], alpha: float, beta: float, phi: float) -> tuple[float, float, list[float]]:
    level, trend = series[0], series[1] - series[0]
    errors = []
    for x in series[1:]:
        predicted = level + phi * trend
        errors.append(x - predicted)
        new_level = alpha * x + (1 - alpha) * predicted
        trend = beta * (new_level - level) + (1 - beta) * phi * trend
        level = new_level
    return level, trend, errors


def _best(series: list[float], phis: list[float]) -> tuple[float, float, float, float, float, list[float]]:
    best = None
    for alpha in GRID:
        for beta in GRID:
            for phi in phis:
                level, trend, errors = _damped(series, alpha, beta, phi)
                sse = sum(e * e for e in errors)
                if best is None or sse < best[0]:
                    best = (sse, alpha, beta, phi, level, trend, errors)
    _, alpha, beta, phi, level, trend, errors = best
    return alpha, beta, phi, level, trend, errors


def holt(series: list[float], horizon: int) -> Fit | None:
    if len(series) < 3:
        return None
    _, _, _, level, trend, errors = _best(series, [1.0])
    return Fit("holt", [level + h * trend for h in range(1, horizon + 1)], errors)


def ets_damped(series: list[float], horizon: int) -> Fit | None:
    if len(series) < 4:
        return None
    _, _, phi, level, trend, errors = _best(series, PHI)
    points = []
    damping = 0.0
    for h in range(1, horizon + 1):
        damping += phi ** h
        points.append(level + damping * trend)
    return Fit("ets_damped", points, errors)


def _ses(series: list[float], alpha: float) -> tuple[float, list[float]]:
    level = series[0]
    errors = []
    for x in series[1:]:
        errors.append(x - level)
        level = alpha * x + (1 - alpha) * level
    return level, errors


def theta(series: list[float], horizon: int) -> Fit | None:
    n = len(series)
    if n < 4:
        return None
    mean_t = (n - 1) / 2
    mean_y = sum(series) / n
    slope = sum((t - mean_t) * (y - mean_y) for t, y in enumerate(series)) / sum((t - mean_t) ** 2 for t in range(n))
    alpha, level, errors = min(((a, *_ses(series, a)) for a in GRID), key=lambda r: sum(e * e for e in r[2]))
    drift = slope / 2
    # the drift also corrects the in-sample one-step forecasts, as the forecast does
    errors = [e - drift for e in errors]
    tail = (1 - alpha) ** n / alpha
    points = [level + drift * ((h - 1) + 1 / alpha - tail) for h in range(1, horizon + 1)]
    return Fit("theta", points, errors)


MODELS: dict[str, Callable[[list[float], int], Fit | None]] = {
    "naive": naive, "seasonal_naive": seasonal_naive, "holt": holt, "ets_damped": ets_damped, "theta": theta}
