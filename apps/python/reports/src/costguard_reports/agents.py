"""Spend of each AI agent over the next days: gradient boosting against simple models, chosen by backtest (ADR 0045).

An agent spends by the day, with a week in it (less on weekends) and steps when someone starts or stops a job; a
month is too coarse for it. The history is agent_spend_by_day of ClickHouse. The candidates:

- naive: yesterday repeats;
- weekly: the same weekday a week ago;
- mean7: the mean of the last week;
- gbm: gradient boosting of small regression trees on the last weeks and the weekday, in plain Python like the other
  models (ADR 0034). It learns how a day differs from the mean of the week before it, so its forecast keeps to the
  level of the agent and adds the week's shape and the recent swings to it. Many days ahead it forecasts day by day,
  each forecast feeding the next.
  Right after a step in the level its features, measured from the week before, lag behind; there the week
  repeated wins, and the backtest picks it.

Each model is backtested on the last weeks (origins a week apart, two weeks ahead each) and the one with the smallest
WAPE wins, as the monthly forecast does. The interval comes from the backtest errors of the winner, not from its
in-sample errors: those of the boosting are too good to trust.
"""

from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, timedelta

MIN_DAYS = 42      # four weeks for the features and two to learn from
HISTORY_DAYS = 182  # older days say little about an agent and slow the boosting down
BACKTEST_HORIZON = 14
ORIGINS = 4
LAGS = (1, 2, 3, 4, 5, 6, 7, 14)


@dataclass(frozen=True)
class Tree:
    """A regression tree of depth at most 2: feature, threshold, then the two sides (a tree or a leaf value)."""
    feature: int
    threshold: float
    left: Tree | float
    right: Tree | float

    def __call__(self, x: list[float]) -> float:
        node: Tree | float = self
        while isinstance(node, Tree):
            node = node.left if x[node.feature] <= node.threshold else node.right
        return node


def _split(rows: list[list[float]], targets: list[float], index: list[int], min_leaf: int
           ) -> tuple[int, float, list[int], list[int]] | None:
    """The split of the rows in index that lowers the squared error most, or None when none does."""
    n = len(index)
    if n < 2 * min_leaf:
        return None
    total = sum(targets[i] for i in index)
    best_gain, best = 1e-12, None
    for f in range(len(rows[0])):
        order = sorted(index, key=lambda i: rows[i][f])
        left_sum = 0.0
        for k in range(n - 1):
            left_sum += targets[order[k]]
            left_n = k + 1
            if left_n < min_leaf or n - left_n < min_leaf:
                continue
            a, b = rows[order[k]][f], rows[order[k + 1]][f]
            if a == b:
                continue
            right_sum = total - left_sum
            gain = left_sum ** 2 / left_n + right_sum ** 2 / (n - left_n) - total ** 2 / n
            if gain > best_gain:
                best_gain, best = gain, (f, (a + b) / 2, k)
    if best is None:
        return None
    f, threshold, k = best
    order = sorted(index, key=lambda i: rows[i][f])
    return f, threshold, order[:k + 1], order[k + 1:]


def _tree(rows: list[list[float]], targets: list[float], index: list[int], depth: int, min_leaf: int
          ) -> Tree | float:
    mean = sum(targets[i] for i in index) / len(index)
    if depth == 0:
        return mean
    split = _split(rows, targets, index, min_leaf)
    if split is None:
        return mean
    f, threshold, left, right = split
    return Tree(f, threshold, _tree(rows, targets, left, depth - 1, min_leaf),
                _tree(rows, targets, right, depth - 1, min_leaf))


@dataclass(frozen=True)
class Boosting:
    base: float
    rate: float
    trees: list[Tree | float]

    def __call__(self, x: list[float]) -> float:
        return self.base + self.rate * sum(t(x) if isinstance(t, Tree) else t for t in self.trees)


def boost(rows: list[list[float]], targets: list[float], rounds: int = 40, rate: float = 0.1, depth: int = 2,
          min_leaf: int = 5) -> Boosting:
    """Gradient boosting with the squared loss: each tree fits what the trees before it left."""
    base = sum(targets) / len(targets)
    residual = [t - base for t in targets]
    trees: list[Tree | float] = []
    index = list(range(len(rows)))
    for _ in range(rounds):
        tree = _tree(rows, residual, index, depth, min_leaf)
        if not isinstance(tree, Tree):
            break  # nothing left to learn
        trees.append(tree)
        residual = [r - rate * tree(x) for r, x in zip(residual, rows, strict=True)]
    return Boosting(base, rate, trees)


def _features(history: list[float], weekday: int) -> tuple[list[float], float]:
    """The features of the next day after history, and the mean of the last week they are measured from."""
    week = history[-7:]
    mean7 = sum(week) / 7
    mean28 = sum(history[-28:]) / len(history[-28:])
    lags = [history[-k] - mean7 for k in LAGS]
    return [*lags, mean28 - mean7, float(weekday)], mean7


def _gbm(series: list[float], start: date, horizon: int) -> list[float] | None:
    if len(series) < MIN_DAYS:
        return None
    rows, targets = [], []
    for t in range(28, len(series)):
        x, mean7 = _features(series[:t], (start + timedelta(days=t)).weekday())
        rows.append(x)
        targets.append(series[t] - mean7)
    model = boost(rows, targets)
    history, out = list(series), []
    for h in range(horizon):
        x, mean7 = _features(history, (start + timedelta(days=len(series) + h)).weekday())
        value = max(mean7 + model(x), 0.0)
        out.append(value)
        history.append(value)
    return out


def _naive(series: list[float], start: date, horizon: int) -> list[float] | None:
    return [series[-1]] * horizon


def _weekly(series: list[float], start: date, horizon: int) -> list[float] | None:
    if len(series) < 7:
        return None
    return [series[len(series) - 7 + h % 7] for h in range(horizon)]


def _mean7(series: list[float], start: date, horizon: int) -> list[float] | None:
    if len(series) < 7:
        return None
    return [sum(series[-7:]) / 7] * horizon


MODELS: dict[str, Callable[[list[float], date, int], list[float] | None]] = {
    "naive": _naive, "weekly": _weekly, "mean7": _mean7, "gbm": _gbm}


@dataclass(frozen=True)
class Score:
    model: str
    wape: float
    errors: list[float]


def backtest(series: list[float], start: date) -> list[Score]:
    """Scores of the models over the last weeks, best first; empty for a short history."""
    n = len(series)
    origins = [n - BACKTEST_HORIZON - 7 * k for k in range(ORIGINS)]
    origins = [o for o in origins if o >= MIN_DAYS]
    if not origins:
        return []
    scores = []
    for name, model in MODELS.items():
        errors, actual = [], 0.0
        for origin in origins:
            test = series[origin:origin + BACKTEST_HORIZON]
            points = model(series[:origin], start, len(test))
            if points is None:
                break
            errors += [a - p for a, p in zip(test, points, strict=True)]
            actual += sum(abs(a) for a in test)
        else:
            wape = sum(abs(e) for e in errors) / actual if actual > 0 else 0.0
            scores.append(Score(name, round(wape, 4), errors))
    return sorted(scores, key=lambda s: (s.wape, s.model))


@dataclass(frozen=True)
class AgentForecast:
    model: str
    wape: float | None
    challenger: str | None
    challenger_wape: float | None
    days: list[tuple[date, float]]
    total: float
    low: float
    high: float


def forecast(history: list[tuple[date, float]], today: date, horizon: int = 30) -> AgentForecast | None:
    """The next horizon days after yesterday; None for an agent with no spend in the history."""
    end = today - timedelta(days=1)
    start = end - timedelta(days=HISTORY_DAYS - 1)
    by_day = {d: v for d, v in history if start <= d <= end}
    if not any(v > 0 for v in by_day.values()):
        return None
    first = min(d for d, v in by_day.items() if v > 0)
    series = [by_day.get(first + timedelta(days=i), 0.0) for i in range((end - first).days + 1)]
    scores = backtest(series, first)
    name = scores[0].model if scores else "mean7"
    points = MODELS[name](series, first, horizon)
    if points is None:  # shorter than a week
        points = [sum(series) / len(series)] * horizon
    points = [max(p, 0.0) for p in points]
    errors = scores[0].errors if scores else [v - sum(series) / len(series) for v in series]
    sigma = math.sqrt(sum(e * e for e in errors) / len(errors)) if errors else 0.0
    total = sum(points)
    width = 1.96 * sigma * math.sqrt(horizon)
    days = [(end + timedelta(days=h + 1), round(p, 2)) for h, p in enumerate(points)]
    challenger = scores[1] if len(scores) > 1 else None
    return AgentForecast(name, scores[0].wape if scores else None, challenger.model if challenger else None,
                         challenger.wape if challenger else None, days, round(total, 2),
                         round(max(total - width, 0.0), 2), round(total + width, 2))
