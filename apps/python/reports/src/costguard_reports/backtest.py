"""Backtest: which model forecasts this series best, the champion, and the next best, the challenger (ADR 0034).

Rolling origin: the last ORIGINS months, one after another, are hidden; every model is fitted on what comes before
and forecasts up to `horizon` months ahead. The scores over all those forecasts:

- WAPE, the sum of absolute errors over the sum of actual spend: what share of the money the forecast missed;
- MASE, the mean absolute error over the mean absolute month-to-month change of the history: below 1 the model
  beats repeating last month.

The champion has the lowest WAPE, MASE breaks a tie. Below MIN_MONTHS there is nothing to test on: no champion.
"""

from __future__ import annotations

from dataclasses import dataclass

from costguard_reports.models import MODELS

MIN_MONTHS = 7
ORIGINS = 6


@dataclass(frozen=True)
class Score:
    model: str
    wape: float
    mase: float
    forecasts: int


def _scale(series: list[float]) -> float:
    steps = [abs(b - a) for a, b in zip(series, series[1:], strict=False)]
    mean = sum(steps) / len(steps) if steps else 0.0
    return mean if mean > 0 else 1.0


def backtest(series: list[float], horizon: int = 3) -> list[Score]:
    """Scores of every model that could be fitted at every origin, best first; empty for a short history."""
    n = len(series)
    if n < MIN_MONTHS:
        return []
    origins = range(max(4, n - ORIGINS), n)
    errors: dict[str, list[float]] = {}
    actual: dict[str, list[float]] = {}
    failed: set[str] = set()
    for origin in origins:
        train, test = series[:origin], series[origin:origin + horizon]
        for name, model in MODELS.items():
            fit = model(train, len(test))
            if fit is None:
                failed.add(name)
                continue
            errors.setdefault(name, []).extend(a - max(p, 0.0) for a, p in zip(test, fit.points, strict=True))
            actual.setdefault(name, []).extend(test)
    scale = _scale(series)
    scores = []
    for name, e in errors.items():
        if name in failed:
            continue  # scored on fewer origins than the others, so not comparable
        total = sum(abs(a) for a in actual[name])
        wape = sum(abs(x) for x in e) / total if total > 0 else 0.0
        mase = sum(abs(x) for x in e) / len(e) / scale
        scores.append(Score(name, round(wape, 4), round(mase, 4), len(e)))
    return sorted(scores, key=lambda s: (s.wape, s.mase, s.model))
